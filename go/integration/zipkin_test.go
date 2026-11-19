package integration

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/trace"

	zipkin "github.com/grow-codes-packages/zipkin/go"
)

func randomHex(t *testing.T, n int) string {
	t.Helper()
	b := make([]byte, n/2)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}

func setup(t *testing.T) *zipkin.Client {
	t.Helper()
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_ZIPKIN_URL") == "" {
		t.Skip("GROW_ZIPKIN_URL is not set; run through docker/compose.yml")
	}
	c, err := zipkin.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func spanNames(spans []zipkin.ZipkinSpan) []string {
	out := make([]string, len(spans))
	for i, s := range spans {
		out[i] = s.Name
	}
	slices.Sort(out)
	return out
}

// TestZipkinAPI calls every Zipkin-specific function against the real server with a
// round-trip assertion (report → query back).
func TestZipkinAPI(t *testing.T) {
	client := setup(t)
	ctx := t.Context()
	q := client.Query()
	run := randomHex(t, 8)
	front, back, lonely := "grow-go-"+run+"-front", "grow-go-"+run+"-back", "grow-go-"+run+"-lonely"
	traceA := randomHex(t, 32)
	traceB := randomHex(t, 16)
	now := time.Now()
	ts := now.Add(-time.Second).UnixMicro()

	t.Run("health ping version", func(t *testing.T) {
		hs, err := client.Health(ctx)
		if err != nil || !hs.Healthy || hs.Status != "UP" {
			t.Fatalf("health %+v %v", hs, err)
		}
		if d, err := client.Ping(ctx); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		want := os.Getenv("ZIPKIN_VERSION")
		if want == "" {
			want = "3.6.1"
		}
		if v, err := client.ServerVersion(ctx); err != nil || v != want {
			t.Fatalf("version %q %v (want %q)", v, err, want)
		}
		if info, err := client.ServerInfo(ctx); err != nil || info.Version != want || info.Commit == "" {
			t.Fatalf("server info %+v %v", info, err)
		}
	})

	t.Run("report", func(t *testing.T) {
		spansA := []zipkin.ZipkinSpan{
			{
				TraceID: strings.ToUpper(traceA), ID: "0000000000000001", Kind: "server", Name: "Get /API/Orders",
				Timestamp: ts, Duration: 150000,
				LocalEndpoint: &zipkin.Endpoint{ServiceName: front, IPv4: "10.0.0.1", Port: 8080},
				Annotations:   []zipkin.Annotation{{Timestamp: ts + 1000, Value: "cache.miss"}},
				Tags:          map[string]string{"grow.test": run, "http.method": "GET", "note": "cats and dogs"},
			},
			{
				TraceID: traceA, ParentID: "0000000000000001", ID: "0000000000000002", Kind: zipkin.KindClient, Name: "get /orders",
				Timestamp: ts + 5000, Duration: 100000,
				LocalEndpoint:  &zipkin.Endpoint{ServiceName: front},
				RemoteEndpoint: &zipkin.Endpoint{ServiceName: back, Port: 9000},
				Tags:           map[string]string{"grow.test": run},
			},
			{
				TraceID: traceA, ParentID: "0000000000000002", ID: "0000000000000003", Kind: zipkin.KindServer, Name: "get /orders",
				Timestamp: ts + 10000, Duration: 80000,
				LocalEndpoint: &zipkin.Endpoint{ServiceName: back},
				Tags:          map[string]string{"grow.test": run, "error": "boom"},
			},
		}
		if err := client.Reporter().Report(ctx, spansA); err != nil {
			t.Fatal(err)
		}
		r, err := zipkin.NewReporterFromEnv(zipkin.ReporterOptions{Compression: zipkin.CompressionGzip, MaxBatchSize: 1})
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = r.Close() }()
		spanB := []zipkin.ZipkinSpan{{
			TraceID: strings.ToUpper(traceB), ID: "00000000000000B1", Name: "lonely-op", Timestamp: ts, Duration: 5000,
			LocalEndpoint: &zipkin.Endpoint{ServiceName: lonely}, Tags: map[string]string{"grow.test": run},
		}}
		if err := r.Report(ctx, spanB); err != nil {
			t.Fatal(err)
		}
		if err := r.Report(ctx, []zipkin.ZipkinSpan{{TraceID: "zz", ID: "1"}}); !errors.Is(err, zipkin.ErrValidation) {
			t.Fatalf("invalid span: %v", err)
		}
		// Client.CreateReporter shares the client's connections; the span is read back.
		cr, err := client.CreateReporter(zipkin.ReporterOptions{Compression: zipkin.CompressionGzip})
		if err != nil {
			t.Fatal(err)
		}
		traceC := randomHex(t, 32)
		if err := cr.Report(ctx, []zipkin.ZipkinSpan{{
			TraceID: traceC, ID: "00000000000000c1", Name: "created-reporter", Timestamp: ts, Duration: 1000,
			LocalEndpoint: &zipkin.Endpoint{ServiceName: lonely},
		}}); err != nil {
			t.Fatal(err)
		}
		if spans, err := q.GetTraceSpans(ctx, traceC); err != nil || len(spans) != 1 || spans[0].Name != "created-reporter" {
			t.Fatalf("created reporter read back %v %v", spans, err)
		}
	})

	t.Run("getTrace", func(t *testing.T) {
		spans, err := q.GetTraceSpans(ctx, traceA)
		if err != nil || len(spans) != 3 {
			t.Fatalf("spans %v %v", spans, err)
		}
		if names := spanNames(spans); !slices.Equal(names, []string{"get /api/orders", "get /orders", "get /orders"}) {
			t.Fatalf("names are lower-cased by Zipkin: %v", names)
		}
		missing, err := q.GetTraceSpans(ctx, randomHex(t, 32))
		if err != nil || missing != nil {
			t.Fatalf("missing %v %v", missing, err)
		}
		tr, err := q.GetTrace(ctx, traceA)
		if err != nil || tr == nil || len(tr.Spans) != 3 {
			t.Fatalf("trace %+v %v", tr, err)
		}
		roots := tr.Tree()
		if len(roots) != 1 || roots[0].Span.Name != "get /api/orders" || len(roots[0].Children) != 1 ||
			roots[0].Span.Kind != zipkin.SpanKindServer || roots[0].Span.Events[0].Name != "cache.miss" {
			t.Fatalf("tree %+v", roots)
		}
		if errSpan := tr.Spans[2]; errSpan.Status.Code != zipkin.SpanStatusError || errSpan.Status.Message != "boom" {
			t.Fatalf("status %+v", errSpan)
		}
		trB, err := q.GetTrace(ctx, traceB)
		if err != nil || trB == nil || trB.TraceID != "0000000000000000"+traceB || trB.Spans[0].ServiceName != lonely {
			t.Fatalf("64-bit trace %+v %v", trB, err)
		}
		waited, err := q.WaitForTrace(ctx, traceA, zipkin.WaitForTraceOptions{MinSpans: 3, Timeout: 30 * time.Second})
		if err != nil || len(waited.Spans) != 3 {
			t.Fatalf("wait %v %v", waited, err)
		}
	})

	t.Run("getTraces", func(t *testing.T) {
		traces, err := q.GetTraces(ctx, zipkin.TracesQuery{
			ServiceName:     front,
			SpanName:        "get /api/orders",
			AnnotationQuery: mustAnnotationQuery(t, map[string]string{"grow.test": run}, []string{"cache.miss"}),
			MinDuration:     100 * time.Millisecond,
			MaxDuration:     200 * time.Millisecond,
			EndTs:           time.Now(),
			Lookback:        time.Hour,
			Limit:           5,
		})
		if err != nil || len(traces) != 1 || traces[0][0].TraceID != traceA {
			t.Fatalf("filters %v %v", traces, err)
		}
		remote, err := q.GetTraces(ctx, zipkin.TracesQuery{RemoteServiceName: back, Limit: 1})
		if err != nil || len(remote) != 1 || remote[0][0].TraceID != traceA {
			t.Fatalf("remote %v %v", remote, err)
		}
		none, err := q.GetTraces(ctx, zipkin.TracesQuery{ServiceName: front, MinDuration: 10 * time.Second})
		if err != nil || len(none) != 0 {
			t.Fatalf("min duration %v %v", none, err)
		}
		if _, err := q.GetTraces(ctx, zipkin.TracesQuery{MaxDuration: time.Second}); !errors.Is(err, zipkin.ErrValidation) {
			t.Fatalf("validation: %v", err)
		}
	})

	t.Run("getTraceMany", func(t *testing.T) {
		traces, err := q.GetTraceMany(ctx, []string{traceB, traceA, randomHex(t, 32), strings.ToUpper(traceA)})
		if err != nil || len(traces) != 2 || traces[0][0].TraceID != traceB || traces[1][0].TraceID != traceA {
			t.Fatalf("many %v %v", traces, err)
		}
		if empty, err := q.GetTraceMany(ctx, nil); err != nil || len(empty) != 0 {
			t.Fatalf("empty %v %v", empty, err)
		}
	})

	t.Run("name lists", func(t *testing.T) {
		services, err := q.ListServices(ctx)
		if err != nil || !slices.Contains(services, front) || !slices.Contains(services, back) || !slices.Contains(services, lonely) {
			t.Fatalf("services %v %v", services, err)
		}
		spans, err := q.ListSpans(ctx, strings.ToUpper(front))
		if err != nil || !slices.Equal(spans, []string{"get /api/orders", "get /orders"}) {
			t.Fatalf("spans %v %v", spans, err)
		}
		ops, err := q.ListOperations(ctx, back)
		if err != nil || !slices.Equal(ops, []string{"get /orders"}) {
			t.Fatalf("operations %v %v", ops, err)
		}
		remotes, err := q.ListRemoteServices(ctx, front)
		if err != nil || !slices.Equal(remotes, []string{back}) {
			t.Fatalf("remote services %v %v", remotes, err)
		}
		keys, err := q.AutocompleteKeys(ctx)
		if err != nil || !slices.Contains(keys, "grow.test") {
			t.Fatalf("autocomplete keys %v %v", keys, err)
		}
		values, err := q.AutocompleteValues(ctx, "grow.test")
		if err != nil || !slices.Contains(values, run) {
			t.Fatalf("autocomplete values %v %v", values, err)
		}
	})

	t.Run("dependencies", func(t *testing.T) {
		links, err := q.GetDependenciesAt(ctx, time.Now(), time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		idx := slices.IndexFunc(links, func(l zipkin.ZipkinDependencyLink) bool { return l.Parent == front && l.Child == back })
		if idx < 0 || links[idx].CallCount < 1 || links[idx].ErrorCount < 1 {
			t.Fatalf("links %+v", links)
		}
		spec, err := q.GetDependencies(ctx, zipkin.DependencyOptions{Start: time.Now().Add(-time.Hour)})
		if err != nil || !slices.Contains(spec, zipkin.DependencyLink{Parent: front, Child: back, CallCount: links[idx].CallCount}) {
			t.Fatalf("spec links %+v %v", spec, err)
		}
	})

	t.Run("searchTraces", func(t *testing.T) {
		start := time.Now().Add(-time.Hour)
		sums, err := q.SearchTraces(ctx, zipkin.TraceSearchQuery{Service: front, Tags: map[string]string{"grow.test": run}, Start: start})
		if err != nil || len(sums) != 1 || sums[0].TraceID != traceA || sums[0].RootName != "get /api/orders" || sums[0].SpanCount != 3 {
			t.Fatalf("search %+v %v", sums, err)
		}
		// A value with " and " is matched client-side.
		sums, err = q.SearchTraces(ctx, zipkin.TraceSearchQuery{Service: front, Tags: map[string]string{"note": "cats and dogs"}, Start: start})
		if err != nil || len(sums) != 1 {
			t.Fatalf("client-side tag %+v %v", sums, err)
		}
		sums, err = q.SearchTraces(ctx, zipkin.TraceSearchQuery{Service: front, Tags: map[string]string{"note": "cats and mice"}, Start: start})
		if err != nil || len(sums) != 0 {
			t.Fatalf("client-side mismatch %+v %v", sums, err)
		}
	})

	t.Run("close", func(t *testing.T) {
		c, err := zipkin.NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		if c.Close() != nil {
			t.Fatal("close")
		}
		if c.Close() != nil {
			t.Fatal("second close")
		}
		if _, err := c.Ping(ctx); !errors.Is(err, zipkin.ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
	})
}

// TestTracing exports OpenTelemetry spans through InitTracing (native exporter) and
// reads them back, covering the helpers, propagation and HTTP adapters.
func TestTracing(t *testing.T) {
	client := setup(t)
	ctx := t.Context()
	service := "grow-go-" + randomHex(t, 8) + "-otel"
	h, err := zipkin.InitTracing(ctx, zipkin.TracingConfig{
		ServiceName:    service,
		ServiceVersion: "1.0.0",
		Propagators:    []string{zipkin.PropagatorTraceContext, zipkin.PropagatorBaggage, zipkin.PropagatorB3, zipkin.PropagatorB3Multi},
		// Installed globally: Inject/Extract and the helpers use the otel globals.
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = h.Shutdown(context.Background()) }()
	tracer := h.Tracer("grow-integration", trace.WithInstrumentationVersion("0.0.1"))

	srv := httptest.NewServer(zipkin.HTTPMiddleware("inbound",
		otelhttp.WithTracerProvider(h.TracerProvider()), otelhttp.WithPropagators(h.Propagator()))(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("traceparent") == "" || r.Header.Get("b3") == "" || r.Header.Get("X-B3-TraceId") == "" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		})))
	defer srv.Close()
	httpClient := &http.Client{Transport: zipkin.HTTPTransport(http.DefaultTransport,
		otelhttp.WithTracerProvider(h.TracerProvider()), otelhttp.WithPropagators(h.Propagator()))}

	var traceID string
	_, err = zipkin.WithSpan(ctx, "otel-root", func(ctx context.Context) (struct{}, error) {
		id, ok := zipkin.CurrentTraceID(ctx)
		if !ok {
			t.Fatal("no current trace id")
		}
		traceID = id
		if sid, ok := zipkin.CurrentSpanID(ctx); !ok || len(sid) != 16 || !zipkin.CurrentSpan(ctx).IsRecording() {
			t.Fatal("current span")
		}
		if f := zipkin.TraceFields(ctx); f["trace_id"] != id {
			t.Fatalf("trace fields %v", f)
		}
		zipkin.AddEvent(ctx, "checkpoint", map[string]any{"step": 1})
		carrier := map[string]string{}
		zipkin.Inject(ctx, carrier)
		if !strings.Contains(carrier["traceparent"], id) || !strings.HasPrefix(carrier["b3"], id) || carrier["x-b3-traceid"] != id {
			t.Fatalf("carrier %v", carrier)
		}
		if got := trace.SpanContextFromContext(zipkin.Extract(context.Background(), carrier)).TraceID().String(); got != id {
			t.Fatalf("extract %s", got)
		}
		hdr := http.Header{}
		zipkin.InjectHeaders(ctx, hdr)
		if got := trace.SpanContextFromContext(zipkin.ExtractHeaders(context.Background(), hdr)).TraceID().String(); got != id {
			t.Fatalf("extract headers %s", got)
		}
		err := zipkin.Traced(ctx, "otel-client", func(ctx context.Context) error {
			req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/ping", nil)
			res, err := httpClient.Do(req)
			if err != nil {
				return err
			}
			_ = res.Body.Close()
			if res.StatusCode != http.StatusNoContent {
				t.Errorf("propagation headers missing: %d", res.StatusCode)
			}
			return nil
		}, zipkin.WithSpanKind(trace.SpanKindClient), zipkin.WithSpanAttributes(map[string]any{"peer.service": "grow-go-peer", "n": 7, "flag": true}))
		if err != nil {
			return struct{}{}, err
		}
		_ = zipkin.Traced(ctx, "otel-fail", func(ctx context.Context) error {
			zipkin.RecordException(ctx, errors.New("recorded"))
			zipkin.SetError(ctx, errors.New("otel-fail"))
			return nil
		})
		return struct{}{}, nil
	}, zipkin.WithTracer(tracer), zipkin.WithSpanKind(trace.SpanKindServer))
	if err != nil {
		t.Fatal(err)
	}
	if err := h.ForceFlush(ctx); err != nil {
		t.Fatal(err)
	}

	tr, err := client.Query().WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{MinSpans: 5, Timeout: 30 * time.Second, Interval: 200 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	byName := map[string]zipkin.Span{}
	for _, s := range tr.Spans {
		byName[s.Name] = s
	}
	root, cl, fail := byName["otel-root"], byName["otel-client"], byName["otel-fail"]
	if root.Kind != zipkin.SpanKindServer || root.ServiceName != service || len(root.Events) != 1 ||
		root.Events[0].Name != "checkpoint" || root.Events[0].Attributes["step"] != int64(1) ||
		root.Attributes["otel.scope.name"] != "grow-integration" {
		t.Fatalf("root %+v", root)
	}
	if cl.Kind != zipkin.SpanKindClient || cl.ParentSpanID != root.SpanID || cl.Attributes["n"] != "7" || cl.Attributes["flag"] != "true" {
		t.Fatalf("client %+v", cl)
	}
	if fail.Status.Code != zipkin.SpanStatusError || fail.Status.Message != "otel-fail" || len(fail.Events) == 0 {
		t.Fatalf("fail %+v", fail)
	}
	if len(tr.Spans) < 5 { // root, client, fail, HTTP client span and HTTP server span
		t.Fatalf("spans %d", len(tr.Spans))
	}
	raw, err := client.Query().GetTraceSpans(ctx, traceID)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range raw {
		if s.Name == "otel-client" && (s.RemoteEndpoint == nil || s.RemoteEndpoint.ServiceName != "grow-go-peer") {
			t.Fatalf("remote endpoint %+v", s)
		}
	}
	services, err := client.Query().ListServices(ctx)
	if err != nil || !slices.Contains(services, service) {
		t.Fatalf("services %v %v", services, err)
	}
	if err := h.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
}

func mustAnnotationQuery(t *testing.T, tags map[string]string, annotations []string) string {
	t.Helper()
	q, err := zipkin.BuildAnnotationQuery(tags, annotations)
	if err != nil {
		t.Fatal(err)
	}
	return q
}

// TestClientInitTracing starts tracing through Client.InitTracing (the client's endpoint
// and connections); Client.Close flushes the spans, which a second client reads back.
func TestClientInitTracing(t *testing.T) {
	ctx := t.Context()
	reader := setup(t) // loads /bootstrap/env
	c, err := zipkin.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	service := "grow-go-" + randomHex(t, 8) + "-client-otel"
	h, err := c.InitTracing(ctx, zipkin.TracingConfig{ServiceName: service, DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	_, span := h.Tracer("grow-integration").Start(ctx, "client-init-tracing")
	traceID := span.SpanContext().TraceID().String()
	span.End()
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	tr, err := reader.Query().WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{MinSpans: 1, Timeout: 30 * time.Second, Interval: 200 * time.Millisecond})
	if err != nil || tr.Spans[0].Name != "client-init-tracing" || tr.Spans[0].ServiceName != service {
		t.Fatalf("trace %+v %v", tr, err)
	}
}
