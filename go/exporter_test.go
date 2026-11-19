package zipkin

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

func syncProvider(t *testing.T, exp sdktrace.SpanExporter, opts ...sdktrace.TracerProviderOption) *sdktrace.TracerProvider {
	t.Helper()
	res := resource.NewSchemaless(attribute.String("service.name", "grow-go-otel"), attribute.String("host.name", "h1"))
	tp := sdktrace.NewTracerProvider(append([]sdktrace.TracerProviderOption{sdktrace.WithResource(res), sdktrace.WithSyncer(exp)}, opts...)...)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })
	return tp
}

func spansByName(spans []ZipkinSpan) map[string]ZipkinSpan {
	out := map[string]ZipkinSpan{}
	for _, s := range spans {
		out[s.Name] = s
	}
	return out
}

func TestSpanExporterMapping(t *testing.T) {
	srv := newIngestServer(t, nil)
	r, err := NewReporter(Config{CommonConfig: CommonConfig{URL: srv.URL}}, ReporterOptions{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = r.Close() }()
	exp := NewSpanExporter(r)
	tp := syncProvider(t, exp, sdktrace.WithRawSpanLimits(sdktrace.SpanLimits{
		AttributeCountLimit: 6, EventCountLimit: 2, AttributeValueLengthLimit: -1,
		AttributePerEventCountLimit: 8, LinkCountLimit: 8, AttributePerLinkCountLimit: 8,
	}))
	tracer := tp.Tracer("grow-smoke", trace.WithInstrumentationVersion("0.0.1"))

	ctx, parent := tracer.Start(t.Context(), "go-parent", trace.WithSpanKind(trace.SpanKindServer),
		trace.WithAttributes(attribute.Bool("flag", true), attribute.Int("n", 7), attribute.Float64("f", 1.5),
			attribute.StringSlice("list", []string{"a", "<b>"}), attribute.String("grow.test", "go")))
	parent.AddEvent("dropped-event") // the oldest event is dropped by the limit of 2
	parent.AddEvent("plain-event")
	parent.AddEvent("go-event", trace.WithAttributes(attribute.String("k", "v"), attribute.Int("n", 2)))
	parent.SetStatus(codes.Ok, "")
	_, child := tracer.Start(ctx, "go-child", trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(attribute.String("server.address", "db.local"), attribute.String("peer.service", "grow-go-peer")))
	child.SetStatus(codes.Error, "go-fail")
	child.End()
	_, prod := tracer.Start(ctx, "go-producer", trace.WithSpanKind(trace.SpanKindProducer),
		trace.WithAttributes(attribute.String("network.peer.address", "10.0.0.9")))
	prod.End()
	_, cons := tracer.Start(ctx, "go-consumer", trace.WithSpanKind(trace.SpanKindConsumer))
	cons.End()
	_, internal := tracer.Start(ctx, "go-internal",
		trace.WithAttributes(attribute.Int("a1", 1), attribute.Int("a2", 2), attribute.Int("a3", 3),
			attribute.Int("a4", 4), attribute.Int("a5", 5), attribute.Int("a6", 6), attribute.Int("a7", 7)))
	internal.End()
	parent.End()

	byName := spansByName(srv.all())
	p, c := byName["go-parent"], byName["go-child"]
	if p.Kind != KindServer || p.ParentID != "" || p.LocalEndpoint == nil || p.LocalEndpoint.ServiceName != "grow-go-otel" ||
		p.Duration < 1 || p.Timestamp <= 0 || p.RemoteEndpoint != nil {
		t.Fatalf("parent %+v", p)
	}
	wantTags := map[string]string{
		"flag": "true", "n": "7", "f": "1.5", "list": `["a","<b>"]`, "grow.test": "go",
		"otel.status_code": "OK", "otel.scope.name": "grow-smoke", "otel.scope.version": "0.0.1",
		"otel.dropped_events_count": "1",
	}
	for k, v := range wantTags {
		if p.Tags[k] != v {
			t.Errorf("parent tag %s = %q, want %q", k, p.Tags[k], v)
		}
	}
	if _, ok := p.Tags["host.name"]; ok {
		t.Error("resource attributes must not become tags")
	}
	if len(p.Annotations) != 2 || p.Annotations[0].Value != "plain-event" || p.Annotations[1].Value != `{"go-event":{"k":"v","n":2}}` {
		t.Fatalf("annotations %+v", p.Annotations)
	}
	if ev := AnnotationToEvent(p.Annotations[1]); ev.Name != "go-event" || ev.Attributes["n"] != int64(2) {
		t.Fatalf("event round trip %+v", ev)
	}
	if c.Kind != KindClient || c.ParentID != p.ID || c.TraceID != p.TraceID || c.RemoteEndpoint == nil ||
		c.RemoteEndpoint.ServiceName != "grow-go-peer" || c.Tags["error"] != "go-fail" || c.Tags["otel.status_code"] != "ERROR" {
		t.Fatalf("child %+v", c)
	}
	if pr := byName["go-producer"]; pr.Kind != KindProducer || pr.RemoteEndpoint == nil || pr.RemoteEndpoint.ServiceName != "10.0.0.9" {
		t.Fatalf("producer %+v", pr)
	}
	if co := byName["go-consumer"]; co.Kind != KindConsumer || co.RemoteEndpoint != nil {
		t.Fatalf("consumer %+v", co)
	}
	if in := byName["go-internal"]; in.Kind != "" || in.Tags["otel.dropped_attributes_count"] != "1" {
		t.Fatalf("internal %+v", in)
	}

	// Round trip through the query model: status, events and tree survive.
	tr := TraceFromZipkin(srv.all())
	if len(tr.Spans) != 5 || len(tr.Tree()) != 1 {
		t.Fatalf("trace %+v", tr)
	}

	if err := exp.ExportSpans(t.Context(), nil); err != nil {
		t.Fatal(err)
	}
	calls := srv.calls.Load()
	if err := exp.Shutdown(t.Context()); err != nil || exp.Shutdown(t.Context()) != nil {
		t.Fatal("shutdown")
	}
	_, late := tracer.Start(t.Context(), "late")
	late.End()
	if srv.calls.Load() != calls {
		t.Fatal("spans after shutdown must be dropped")
	}
	cancelled, cancel := context.WithCancel(t.Context())
	cancel()
	if err := NewSpanExporter(r).Shutdown(cancelled); !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled shutdown: %v", err)
	}
}

func TestOTelTagValue(t *testing.T) {
	cases := map[string]attribute.Value{
		`[true,false]`: attribute.BoolSliceValue([]bool{true, false}),
		`[1,2]`:        attribute.Int64SliceValue([]int64{1, 2}),
		`[1.5]`:        attribute.Float64SliceValue([]float64{1.5}),
		"x":            attribute.StringValue("x"),
		"false":        attribute.BoolValue(false),
		"":             {},
	}
	for want, v := range cases {
		if got := otelTagValue(v); got != want {
			t.Errorf("%v → %q, want %q", v.Type(), got, want)
		}
	}
	if marshalJSON(func() {}) != "" {
		t.Fatal("unencodable value")
	}
}

func TestExportErrors(t *testing.T) {
	srv := newTestServer(t, statusHandler(http.StatusBadRequest))
	r, err := NewReporter(Config{CommonConfig: CommonConfig{URL: srv.URL}}, ReporterOptions{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = r.Close() }()
	exp := NewSpanExporter(r)
	tp := sdktrace.NewTracerProvider()
	_, span := tp.Tracer("x").Start(t.Context(), "op")
	span.End()
	ro, ok := span.(sdktrace.ReadOnlySpan)
	if !ok {
		t.Fatal("not a ReadOnlySpan")
	}
	if err := exp.ExportSpans(t.Context(), []sdktrace.ReadOnlySpan{ro}); !errors.Is(err, ErrValidation) {
		t.Fatalf("export: %v", err)
	}
	// No service.name in the resource → no local endpoint.
	empty := sdktrace.NewTracerProvider(sdktrace.WithResource(resource.Empty()))
	_, s2 := empty.Tracer("").Start(t.Context(), "bare")
	s2.End()
	if z := SDKSpanToZipkin(s2.(sdktrace.ReadOnlySpan)); z.LocalEndpoint.ServiceName != UnknownService || len(z.Tags) != 1 || z.Annotations != nil {
		t.Fatalf("bare %+v", z)
	}
}

func TestTracingConfigFromEnv(t *testing.T) {
	cfg, err := TracingConfigFromEnvWith(TracingConfig{}, map[string]string{"OTEL_SERVICE_NAME": "svc"})
	if err != nil || cfg.ServiceName != "svc" || cfg.Protocol != ProtocolNative || cfg.Endpoint != DefaultURL+SpansPath ||
		strings.Join(cfg.Propagators, ",") != "b3,tracecontext,baggage" {
		t.Fatalf("defaults %+v %v", cfg, err)
	}
	env := map[string]string{
		EnvPrefix + "ENDPOINT": "http://grow:1/api/v2/spans",
		EnvOTelZipkinEndpoint:  "http://otel:2/api/v2/spans",
		EnvPrefix + "URL":      "http://url:3/zipkin",
	}
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{ServiceName: "s"}, env); cfg.Endpoint != "http://grow:1/api/v2/spans" {
		t.Fatal(cfg.Endpoint)
	}
	delete(env, EnvPrefix+"ENDPOINT")
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{ServiceName: "s"}, env); cfg.Endpoint != "http://otel:2/api/v2/spans" {
		t.Fatal(cfg.Endpoint)
	}
	delete(env, EnvOTelZipkinEndpoint)
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{ServiceName: "s"}, env); cfg.Endpoint != "http://url:3/zipkin/api/v2/spans" {
		t.Fatal(cfg.Endpoint)
	}
	env = map[string]string{EnvZipkinURL: "http://vendor:4"}
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{ServiceName: "s"}, env); cfg.Endpoint != "http://vendor:4/api/v2/spans" {
		t.Fatal(cfg.Endpoint)
	}
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{ServiceName: "s", Protocol: ProtocolGRPC}, env); cfg.Endpoint != "" {
		t.Fatalf("OTLP keeps its default endpoint: %q", cfg.Endpoint)
	}
	cfg, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "s", Propagators: []string{"tracecontext", " B3Multi", "baggage", "b3", "tracecontext"}}, map[string]string{})
	if strings.Join(cfg.Propagators, ",") != "b3multi,b3,tracecontext,baggage" {
		t.Fatal(cfg.Propagators)
	}
	if _, err := TracingConfigFromEnvWith(TracingConfig{}, map[string]string{}); !errors.Is(err, ErrConfig) {
		t.Fatalf("missing service name: %v", err)
	}
	t.Setenv("OTEL_SERVICE_NAME", "from-process")
	if cfg, err := TracingConfigFromEnv(TracingConfig{}); err != nil || cfg.ServiceName != "from-process" {
		t.Fatalf("process env: %v %v", cfg, err)
	}
}

func TestInitTracing(t *testing.T) {
	srv := newIngestServer(t, nil)
	h, err := InitTracing(t.Context(), TracingConfig{
		ServiceName:      "grow-init",
		Endpoint:         srv.URL + SpansPath,
		Headers:          map[string]string{"Authorization": "Bearer " + testToken},
		DisableSetGlobal: true,
		Batch:            TracingBatchOptions{ScheduledDelay: time.Millisecond},
	})
	if err != nil {
		t.Fatal(err)
	}
	tracer := h.Tracer("init-test")
	_, err = WithSpan(t.Context(), "init-span", func(ctx context.Context) (int, error) {
		carrier := map[string]string{}
		h.Propagator().Inject(ctx, propagation.MapCarrier(carrier))
		if carrier["traceparent"] == "" || carrier["b3"] == "" || carrier["x-b3-traceid"] != "" {
			t.Errorf("carrier %v", carrier)
		}
		return 1, nil
	}, WithTracer(tracer))
	if err != nil {
		t.Fatal(err)
	}
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	got := srv.all()
	if len(got) != 1 || got[0].Name != "init-span" || got[0].LocalEndpoint.ServiceName != "grow-init" ||
		srv.headers[0].Get("Content-Encoding") != "gzip" || srv.headers[0].Get("Authorization") != "Bearer "+testToken {
		t.Fatalf("got %+v headers %v", got, srv.headers)
	}
	if err := h.Shutdown(t.Context()); err != nil || h.Shutdown(t.Context()) != nil {
		t.Fatal("shutdown")
	}
	if _, err := InitTracing(t.Context(), TracingConfig{ServiceName: "x", Endpoint: "nope"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad endpoint: %v", err)
	}
}

func TestPropagatorPrecedence(t *testing.T) {
	cfg, err := TracingConfigFromEnvWith(TracingConfig{ServiceName: "p", DisableSetGlobal: true}, map[string]string{})
	if err != nil {
		t.Fatal(err)
	}
	h, err := InitTracingWith(t.Context(), cfg, func(context.Context, TracingConfig) (sdktrace.SpanExporter, error) {
		return NewSpanExporter(&Reporter{}), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = h.Shutdown(context.Background()) }()
	carrier := propagation.MapCarrier{
		"traceparent": "00-11111111111111111111111111111111-1111111111111111-01",
		"b3":          "22222222222222222222222222222222-2222222222222222-1",
	}
	sc := trace.SpanContextFromContext(h.Propagator().Extract(t.Context(), carrier))
	if sc.TraceID().String() != "11111111111111111111111111111111" {
		t.Fatalf("W3C must win: %s", sc.TraceID())
	}
	sc = trace.SpanContextFromContext(h.Propagator().Extract(t.Context(), propagation.MapCarrier{
		"x-b3-traceid": "33333333333333333333333333333333", "x-b3-spanid": "3333333333333333", "x-b3-sampled": "1",
	}))
	if sc.TraceID().String() != "33333333333333333333333333333333" {
		t.Fatalf("B3 multi must be extracted: %s", sc.TraceID())
	}
}

func TestNewExporter(t *testing.T) {
	exp, err := NewExporter(t.Context(), TracingConfig{ServiceName: "x", Protocol: ProtocolHTTPProtobuf, Endpoint: "http://127.0.0.1:4318"})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := exp.(*SpanExporter); ok {
		t.Fatal("OTLP protocol must use the OTLP exporter")
	}
	_ = exp.Shutdown(t.Context())
	native, err := NewExporter(t.Context(), TracingConfig{ServiceName: "x"})
	if err != nil {
		t.Fatal(err)
	}
	se, ok := native.(*SpanExporter)
	if !ok || !se.owned || se.reporter.Endpoint() != DefaultURL+SpansPath || se.reporter.compression != CompressionGzip {
		t.Fatalf("native %+v", native)
	}
	_ = native.Shutdown(t.Context())
	if _, err := NewExporter(t.Context(), TracingConfig{ServiceName: "x", Compression: "zstd"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad compression: %v", err)
	}
}

func TestRemoteEndpointAndResourceTags(t *testing.T) {
	srv := newIngestServer(t, nil)
	r, err := NewReporter(Config{CommonConfig: CommonConfig{URL: srv.URL}}, ReporterOptions{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = r.Close() }()
	res := resource.NewSchemaless(attribute.String("service.name", "svc"), attribute.String("service.version", "1.2.3"),
		attribute.String("deployment.environment.name", "staging"), attribute.String("host.name", "h"))
	tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res), sdktrace.WithSyncer(NewSpanExporter(r)))
	defer func() { _ = tp.Shutdown(context.Background()) }()
	tracer := tp.Tracer("t")
	start := func(name string, kind trace.SpanKind, attrs ...attribute.KeyValue) {
		_, s := tracer.Start(t.Context(), name, trace.WithSpanKind(kind), trace.WithAttributes(attrs...))
		s.End()
	}
	start("ip4", trace.SpanKindClient, attribute.String("network.peer.address", "10.1.2.3"), attribute.Int("server.port", 8443))
	start("ip6", trace.SpanKindProducer, attribute.String("peer.service", "kafka"), attribute.String("network.peer.address", "::1"))
	start("addr", trace.SpanKindClient, attribute.String("server.address", "api"), attribute.String("net.peer.name", "db"))
	start("nonstring", trace.SpanKindClient, attribute.Int("peer.service", 7), attribute.String("net.peer.name", "db"))
	start("none", trace.SpanKindClient, attribute.Int("server.port", 80))
	start("server", trace.SpanKindServer, attribute.String("peer.service", "ignored"), attribute.String("service.version", "span"))
	by := spansByName(srv.all())
	// The remote endpoint carries the service name only (the TypeScript mapping table).
	for name, want := range map[string]string{"ip4": "10.1.2.3", "ip6": "kafka", "addr": "api", "nonstring": "db"} {
		if e := by[name].RemoteEndpoint; e == nil || *e != (Endpoint{ServiceName: want}) {
			t.Fatalf("%s %+v", name, e)
		}
	}
	if by["none"].RemoteEndpoint != nil || by["server"].RemoteEndpoint != nil {
		t.Fatal("no remote endpoint without a name or for SERVER spans")
	}
	for k := range by["ip4"].Tags {
		if k == "service.version" || k == "deployment.environment.name" || k == "host.name" {
			t.Fatalf("resource attributes must not become tags: %v", by["ip4"].Tags)
		}
	}
	if by["server"].Tags["service.version"] != "span" {
		t.Fatal("span attributes are kept")
	}
}

func TestTracingEnvVariables(t *testing.T) {
	env := map[string]string{
		EnvPrefix + "PROTOCOL":         "HTTP/PROTOBUF",
		"OTEL_EXPORTER_OTLP_ENDPOINT":  "http://collector:4318/",
		"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "1500",
		EnvPrefix + "COMPRESSION":      "NONE",
		"OTEL_RESOURCE_ATTRIBUTES":     "service.name=res-svc,team=a%20b, ,region=eu",
		"OTEL_TRACES_SAMPLER":          "parentbased_traceidratio",
		"OTEL_TRACES_SAMPLER_ARG":      "0.25",
		"OTEL_PROPAGATORS":             "tracecontext, b3multi,,baggage",
	}
	cfg, err := TracingConfigFromEnvWith(TracingConfig{ResourceAttributes: map[string]any{"region": "explicit"}}, env)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Protocol != ProtocolHTTPProtobuf || cfg.Endpoint != "http://collector:4318/v1/traces" || cfg.Batch.ExportTimeout != 1500*time.Millisecond ||
		cfg.Compression != CompressionNone || cfg.ServiceName != "res-svc" || cfg.ResourceAttributes["team"] != "a b" ||
		cfg.ResourceAttributes["region"] != "explicit" || cfg.Sampler != SamplerParentBasedTraceIDRatio || cfg.SamplerRatio != 0.25 ||
		strings.Join(cfg.Propagators, ",") != "b3multi,tracecontext,baggage" {
		t.Fatalf("cfg %+v", cfg)
	}
	env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] = "http://collector:4318/custom"
	env["OTEL_SERVICE_NAME"] = "named"
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{}, env); cfg.Endpoint != "http://collector:4318/custom" || cfg.ServiceName != "named" {
		t.Fatalf("traces endpoint %+v", cfg)
	}
	grpc := map[string]string{EnvPrefix + "PROTOCOL": "grpc", "OTEL_EXPORTER_OTLP_ENDPOINT": "http://collector:4317", "OTEL_SERVICE_NAME": "s"}
	if cfg, _ := TracingConfigFromEnvWith(TracingConfig{}, grpc); cfg.Endpoint != "http://collector:4317" {
		t.Fatalf("grpc endpoint %q", cfg.Endpoint)
	}
	bad := []map[string]string{
		{EnvPrefix + "PROTOCOL": "thrift"},
		{"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "soon"},
		{"OTEL_EXPORTER_ZIPKIN_TIMEOUT": "0"},
		{EnvPrefix + "COMPRESSION": "zstd"},
		{"OTEL_RESOURCE_ATTRIBUTES": "novalue"},
		{"OTEL_RESOURCE_ATTRIBUTES": "k=%zz"},
		{"OTEL_RESOURCE_ATTRIBUTES": "%zz=v"},
		{"OTEL_TRACES_SAMPLER": "sometimes"},
		{"OTEL_TRACES_SAMPLER": "traceidratio", "OTEL_TRACES_SAMPLER_ARG": "2"},
	}
	for i, b := range bad {
		b["OTEL_SERVICE_NAME"] = "s"
		_, err := TracingConfigFromEnvWith(TracingConfig{}, b)
		if !errors.Is(err, ErrConfig) {
			t.Errorf("case %d: %v", i, err)
			continue
		}
		for k, v := range b {
			if k != "OTEL_SERVICE_NAME" && v != "" && len(v) > 3 && strings.Contains(err.Error(), v) {
				t.Errorf("case %d leaks the value: %v", i, err)
			}
		}
	}
	// The ratio argument only applies to ratio samplers.
	if cfg, err := TracingConfigFromEnvWith(TracingConfig{}, map[string]string{"OTEL_SERVICE_NAME": "s", "OTEL_TRACES_SAMPLER": "always_on", "OTEL_TRACES_SAMPLER_ARG": "x"}); err != nil || cfg.Sampler != SamplerAlwaysOn {
		t.Fatalf("always_on %+v %v", cfg, err)
	}
}

func TestNewExporterOTLPEndpoint(t *testing.T) {
	for _, ep := range []string{"", "http://zipkin:9411/api/v2/spans"} {
		if _, err := NewExporter(t.Context(), TracingConfig{ServiceName: "x", Protocol: ProtocolGRPC, Endpoint: ep}); !errors.Is(err, ErrConfig) {
			t.Errorf("%q: %v", ep, err)
		}
	}
}
