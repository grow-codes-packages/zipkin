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

// ingestServer records the decoded span batches it receives and answers 202.
type ingestServer struct {
	*testServer
	mu      sync.Mutex
	batches [][]ZipkinSpan
	headers []http.Header
	paths   []string
}

func newIngestServer(t *testing.T, statusFor func(n int) int) *ingestServer {
	t.Helper()
	s := &ingestServer{}
	s.testServer = newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		var reader io.Reader = r.Body
		if r.Header.Get("Content-Encoding") == "gzip" {
			zr, err := gzip.NewReader(r.Body)
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			reader = zr
		}
		var spans []ZipkinSpan
		if err := json.NewDecoder(reader).Decode(&spans); err != nil {
			w.Header().Set("Content-Type", "text/*")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte("Expected a JSON_V2 encoded list"))
			return
		}
		s.mu.Lock()
		s.batches = append(s.batches, spans)
		s.headers = append(s.headers, r.Header.Clone())
		s.paths = append(s.paths, r.URL.RequestURI())
		s.mu.Unlock()
		code := http.StatusAccepted
		if statusFor != nil {
			code = statusFor(n)
		}
		w.WriteHeader(code)
	})
	return s
}

func (s *ingestServer) all() []ZipkinSpan {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []ZipkinSpan
	for _, b := range s.batches {
		out = append(out, b...)
	}
	return out
}

func fixtureRequestSpans(t *testing.T, name string) []ZipkinSpan {
	t.Helper()
	var spans []ZipkinSpan
	if err := json.Unmarshal(loadFixture(t, name).Request.Body, &spans); err != nil {
		t.Fatal(err)
	}
	return spans
}

func TestReporter(t *testing.T) {
	t.Run("report sends the v2 JSON batch", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		spans := fixtureRequestSpans(t, "report.json")
		if err := c.Reporter().Report(t.Context(), spans); err != nil {
			t.Fatal(err)
		}
		got := srv.all()
		if len(got) != 4 || got[0].TraceID != spans[0].TraceID || got[3].TraceID != "463ac35cad1c0df6" ||
			got[1].RemoteEndpoint.ServiceName != "grow-backend" || got[0].Annotations[0].Value != "cache.miss" {
			t.Fatalf("got %+v", got)
		}
		h := srv.headers[0]
		if h.Get("Content-Type") != "application/json" || h.Get("Content-Encoding") != "" || srv.paths[0] != SpansPath {
			t.Fatalf("headers %v path %s", h, srv.paths[0])
		}
	})

	t.Run("uppercase ids and kinds are normalized", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		err := c.Reporter().Report(t.Context(), []ZipkinSpan{{TraceID: "463AC35CAD1C0DF6", ID: "00000000000000B1", Kind: "server"}})
		if err != nil {
			t.Fatal(err)
		}
		if got := srv.all()[0]; got.TraceID != "463ac35cad1c0df6" || got.ID != "00000000000000b1" || got.Kind != KindServer {
			t.Fatalf("got %+v", got)
		}
	})

	t.Run("empty report sends nothing", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		if err := newTestClient(t, srv.URL, nil).Reporter().Report(t.Context(), nil); err != nil || srv.calls.Load() != 0 {
			t.Fatalf("err=%v calls=%d", err, srv.calls.Load())
		}
	})

	t.Run("invalid span fails before sending", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		err := newTestClient(t, srv.URL, nil).Reporter().Report(t.Context(), []ZipkinSpan{{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1"}, {TraceID: "zz", ID: "1"}})
		if !errors.Is(err, ErrValidation) || srv.calls.Load() != 0 {
			t.Fatalf("err=%v calls=%d", err, srv.calls.Load())
		}
	})

	t.Run("gzip and batching", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		cfg := Config{CommonConfig: CommonConfig{URL: srv.URL}}
		r, err := NewReporter(cfg, ReporterOptions{Compression: CompressionGzip, MaxBatchSize: 2})
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = r.Close() }()
		spans := fixtureRequestSpans(t, "report.json")
		if err := r.Report(t.Context(), spans); err != nil {
			t.Fatal(err)
		}
		if len(srv.batches) != 2 || len(srv.batches[0]) != 2 || len(srv.batches[1]) != 2 || srv.headers[0].Get("Content-Encoding") != "gzip" {
			t.Fatalf("batches %d headers %v", len(srv.batches), srv.headers)
		}
		if got := srv.all(); got[2].ID != spans[2].ID {
			t.Fatal("order")
		}
		first := r.Close()
		second := r.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if err := r.Report(t.Context(), spans); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
	})

	t.Run("5xx is retried, 400 is permanent", func(t *testing.T) {
		srv := newIngestServer(t, func(n int) int {
			if n == 1 {
				return http.StatusServiceUnavailable
			}
			return http.StatusAccepted
		})
		c := newTestClient(t, srv.URL, nil)
		span := []ZipkinSpan{{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1"}}
		if err := c.Reporter().Report(t.Context(), span); err != nil || srv.calls.Load() != 2 {
			t.Fatalf("err=%v calls=%d", err, srv.calls.Load())
		}
		bad := fixtureServer(t, "report.invalidId")
		bc := newTestClient(t, bad.URL, nil)
		_, err := bc.Reporter().transport.do(t.Context(), &request{Method: http.MethodPost, Path: SpansPath, Body: []byte(`[{"traceId":"zz","id":"1"}]`), ContentType: "application/json", Idempotent: true, Operation: "zipkin.report"})
		if !errors.Is(err, ErrValidation) || !strings.Contains(err.Error(), "lower-hex") || bad.calls.Load() != 1 {
			t.Fatalf("err=%v calls=%d", err, bad.calls.Load())
		}
		big := newTestServer(t, status(http.StatusRequestEntityTooLarge))
		if err := newTestClient(t, big.URL, nil).Reporter().Report(t.Context(), span); !errors.Is(err, ErrValidation) || big.calls.Load() != 1 {
			t.Fatalf("413: %v", err)
		}
	})

	t.Run("endpoint routing", func(t *testing.T) {
		srv := newIngestServer(t, nil)
		span := []ZipkinSpan{{TraceID: "463ac35cad1c0df6", ID: "00000000000000b1"}}
		// Gateway prefix on the same server: the client transport is shared.
		c := newTestClient(t, srv.URL+"/zipkin", nil)
		if c.Reporter().owned || c.Reporter().Endpoint() != srv.URL+"/zipkin/api/v2/spans" {
			t.Fatalf("shared reporter %+v", c.Reporter())
		}
		if err := c.Reporter().Report(t.Context(), span); err != nil || srv.paths[0] != "/zipkin/api/v2/spans" {
			t.Fatalf("err=%v paths=%v", err, srv.paths)
		}
		// Endpoint outside the base path or on another origin: an own transport.
		c2 := newTestClient(t, srv.URL+"/query", func(cfg *Config) { cfg.Endpoint = srv.URL + "/ingest/spans?x=1" })
		if !c2.Reporter().owned {
			t.Fatal("expected an owned transport")
		}
		if err := c2.Reporter().Report(t.Context(), span); err != nil || srv.paths[1] != "/ingest/spans?x=1" {
			t.Fatalf("err=%v paths=%v", err, srv.paths)
		}
		_ = c2.Reporter().Close()
		_ = c2.Close()
		c3 := newTestClient(t, "http://other.invalid:9411", func(cfg *Config) { cfg.Endpoint = srv.URL + SpansPath })
		if !c3.Reporter().owned {
			t.Fatal("other origin must own its transport")
		}
		if err := c3.Reporter().Report(t.Context(), span); err != nil {
			t.Fatal(err)
		}
		if rel, ok := relativePath(c.transport.base, c.transport.base); !ok || rel != "" {
			t.Fatalf("same path: %q %v", rel, ok)
		}
	})

	t.Run("options and constructors", func(t *testing.T) {
		cfg := Config{CommonConfig: CommonConfig{URL: "http://127.0.0.1:9411"}}
		bad := []ReporterOptions{{Compression: "zstd"}, {MaxBatchSize: -1}, {Endpoint: "nope"}}
		for i, o := range bad {
			if _, err := NewReporter(cfg, o); !errors.Is(err, ErrConfig) {
				t.Errorf("case %d: %v", i, err)
			}
		}
		if _, err := NewReporter(Config{CommonConfig: CommonConfig{URL: "ftp://x"}}, ReporterOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad config: %v", err)
		}
		if _, err := NewReporter(Config{CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}, ReporterOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad tls: %v", err)
		}
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "http://a"}, Endpoint: "http://b/x"}); err != nil {
			t.Fatal(err)
		}
		t.Setenv(EnvPrefix+"ENDPOINT", "http://env.example:1/api/v2/spans")
		r, err := NewReporterFromEnv(ReporterOptions{})
		if err != nil || r.Endpoint() != "http://env.example:1/api/v2/spans" {
			t.Fatalf("from env: %v", err)
		}
		_ = r.Close()
		t.Setenv(EnvPrefix+"URL", "ftp://bad")
		if _, err := NewReporterFromEnv(ReporterOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})
}
