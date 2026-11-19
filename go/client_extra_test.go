package zipkin

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// spanSink records the spans POSTed to it (any path) and the request headers.
type spanSink struct {
	*testServer
	mu      sync.Mutex
	spans   []ZipkinSpan
	paths   []string
	headers []http.Header
}

func newSpanSink(t *testing.T) *spanSink {
	t.Helper()
	s := &spanSink{}
	s.testServer = newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if r.Method != http.MethodPost {
			_, _ = w.Write([]byte(`{"zipkin":{"version":"3.6.1","commit":"b2b0c98"}}`))
			return
		}
		var body io.Reader = r.Body
		if r.Header.Get("Content-Encoding") == "gzip" {
			zr, err := gzip.NewReader(r.Body)
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			body = zr
		}
		var spans []ZipkinSpan
		_ = json.NewDecoder(body).Decode(&spans)
		s.mu.Lock()
		s.spans = append(s.spans, spans...)
		s.paths = append(s.paths, r.URL.Path)
		s.headers = append(s.headers, r.Header.Clone())
		s.mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
	})
	return s
}

func (s *spanSink) snapshot() ([]ZipkinSpan, []string, []http.Header) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]ZipkinSpan(nil), s.spans...), append([]string(nil), s.paths...), append([]http.Header(nil), s.headers...)
}

func TestClientServerInfo(t *testing.T) {
	sink := newSpanSink(t)
	info, err := newTestClient(t, sink.URL, nil).ServerInfo(t.Context())
	if err != nil || info.Version != "3.6.1" || info.Commit != "b2b0c98" {
		t.Fatalf("info %+v %v", info, err)
	}
	ts404 := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
		loadFixture(t, "deleteTrace.unsupported").write(w)
	})
	if info, err := newTestClient(t, ts404.URL, nil).ServerInfo(t.Context()); err != nil || info != (ServerInfo{}) {
		t.Fatalf("404 info %+v %v", info, err)
	}
	ts500 := newTestServer(t, status(http.StatusInternalServerError))
	if _, err := newTestClient(t, ts500.URL, func(c *Config) { c.Retry = fastRetry(0) }).ServerInfo(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("500: %v", err)
	}
	tsBad := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("nope")) })
	if _, err := newTestClient(t, tsBad.URL, nil).ServerInfo(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("decode: %v", err)
	}
}

func TestClientCreateReporter(t *testing.T) {
	sink := newSpanSink(t)
	other := newSpanSink(t)
	c := newTestClient(t, sink.URL, func(cfg *Config) { cfg.Headers = map[string]string{"X-Gw": "1"} })
	span := ZipkinSpan{TraceID: "463AC35CAD1C0DF6", ID: "00000000000000B1", Name: "op"}

	gz, err := c.CreateReporter(ReporterOptions{Compression: CompressionGzip, MaxBatchSize: 1})
	if err != nil || gz.Endpoint() != sink.URL+SpansPath {
		t.Fatalf("shared reporter: %v", err)
	}
	if err := gz.Report(t.Context(), []ZipkinSpan{span, span}); err != nil {
		t.Fatal(err)
	}
	spans, paths, headers := sink.snapshot()
	if len(spans) != 2 || len(paths) != 2 || spans[0].TraceID != "463ac35cad1c0df6" || headers[0].Get("X-Gw") != "1" {
		t.Fatalf("shared: %+v %v", spans, paths)
	}

	own, err := c.CreateReporter(ReporterOptions{Endpoint: other.URL + "/collector/spans"})
	if err != nil {
		t.Fatal(err)
	}
	if err := own.Report(t.Context(), []ZipkinSpan{span}); err != nil {
		t.Fatal(err)
	}
	_, opaths, oheaders := other.snapshot()
	if len(opaths) != 1 || opaths[0] != "/collector/spans" || oheaders[0].Get("X-Gw") != "1" {
		t.Fatalf("owned: %v", opaths)
	}
	if _, err := c.CreateReporter(ReporterOptions{Compression: "zstd"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad options: %v", err)
	}

	_ = c.Close()
	if err := own.Report(t.Context(), []ZipkinSpan{span}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("owned reporter after client close: %v", err)
	}
	if _, err := c.CreateReporter(ReporterOptions{}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("create after close: %v", err)
	}
}

func TestClientInitTracing(t *testing.T) {
	t.Run("native to the client endpoint, flushed by Close", func(t *testing.T) {
		sink := newSpanSink(t)
		c := newTestClient(t, sink.URL+"/gw", func(cfg *Config) { cfg.Headers = map[string]string{"X-Gw": "client"} })
		h, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "Client-Svc", DisableSetGlobal: true, Compression: CompressionNone})
		if err != nil {
			t.Fatal(err)
		}
		if h.Config().Endpoint != sink.URL+"/gw"+SpansPath || h.Config().Protocol != ProtocolNative {
			t.Fatalf("config: %+v", h.Config())
		}
		_, span := h.Tracer("t").Start(t.Context(), "work")
		span.End()
		_ = c.Close()
		spans, paths, headers := sink.snapshot()
		if len(spans) != 1 || spans[0].Name != "work" || spans[0].LocalEndpoint.ServiceName != "Client-Svc" {
			t.Fatalf("spans: %+v", spans)
		}
		if paths[0] != "/gw"+SpansPath || headers[0].Get("X-Gw") != "client" {
			t.Fatalf("path %v headers %v", paths, headers)
		}
		if _, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "s"}); !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), "closed") {
			t.Fatalf("after close: %v", err)
		}
	})

	t.Run("tracing headers and explicit endpoint", func(t *testing.T) {
		sink := newSpanSink(t)
		other := newSpanSink(t)
		c := newTestClient(t, sink.URL, func(cfg *Config) { cfg.Headers = map[string]string{"X-Gw": "client", "X-Keep": "1"} })
		h, err := c.InitTracing(t.Context(), TracingConfig{
			ServiceName:      "svc",
			DisableSetGlobal: true,
			Endpoint:         other.URL + "/x/spans",
			Headers:          map[string]string{"X-Gw": "tracing"},
			TLS:              TLSConfig{InsecureSkipVerify: true},
		})
		if err != nil {
			t.Fatal(err)
		}
		_, span := h.Tracer("t").Start(t.Context(), "op")
		span.End()
		if err := h.Shutdown(t.Context()); err != nil {
			t.Fatal(err)
		}
		spans, paths, headers := other.snapshot()
		if len(spans) != 1 || paths[0] != "/x/spans" || headers[0].Get("X-Gw") != "tracing" || headers[0].Get("X-Keep") != "1" {
			t.Fatalf("spans %+v paths %v headers %v", spans, paths, headers)
		}
		if s, _, _ := sink.snapshot(); len(s) != 0 {
			t.Fatal("client endpoint must not receive spans")
		}
	})

	t.Run("otlp needs a collector endpoint; env errors", func(t *testing.T) {
		c := newTestClient(t, "http://127.0.0.1:9", nil)
		if _, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "s", Protocol: ProtocolGRPC, DisableSetGlobal: true}); !errors.Is(err, ErrConfig) {
			t.Fatalf("otlp: %v", err)
		}
		t.Setenv(EnvPrefix+"PROTOCOL", "smoke-signals")
		if _, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "s"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("env: %v", err)
		}
	})

	t.Run("bad compression from the factory", func(t *testing.T) {
		c := newTestClient(t, "http://127.0.0.1:9", nil)
		if _, err := c.exporterFactory(t.Context(), TracingConfig{ServiceName: "s", Protocol: ProtocolNative, Endpoint: "nope", Compression: CompressionGzip}); !errors.Is(err, ErrConfig) {
			t.Fatalf("factory: %v", err)
		}
	})
}
