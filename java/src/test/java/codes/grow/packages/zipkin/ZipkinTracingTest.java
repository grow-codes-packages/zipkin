package codes.grow.packages.zipkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.MockServer;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.tracing.Spans;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import codes.grow.packages.zipkin.tracing.TracingConfig.Protocol;
import codes.grow.packages.zipkin.tracing.TracingConfig.SamplerType;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZipkinTracingTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;
  private static final String TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    server.fallback(ex -> MockServer.send(ex, 202, ""));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private static TracingConfig.Builder cfg() {
    return TracingConfig.builder("checkout").setGlobal(false);
  }

  private static Map<String, String> env(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  // -------------------------------------------------------------------------------------------
  // configuration

  @Test
  void propagatorOrderPutsB3First() {
    assertThat(
            ZipkinTracing.orderPropagators(
                List.of("tracecontext", " B3 ", "baggage", "b3multi", "b3")))
        .containsExactly("b3", "b3multi", "tracecontext", "baggage");
    assertThat(ZipkinTracing.DEFAULT_PROPAGATORS).containsExactly("tracecontext", "baggage", "b3");
  }

  @Test
  void otlpEndpoints() {
    assertThat(ZipkinTracing.resolveOtlpEndpoint(Protocol.HTTP_PROTOBUF, "http://c:4318/"))
        .isEqualTo("http://c:4318/v1/traces");
    assertThat(ZipkinTracing.resolveOtlpEndpoint(Protocol.HTTP_PROTOBUF, "http://c:4318/x"))
        .isEqualTo("http://c:4318/x");
    assertThat(ZipkinTracing.resolveOtlpEndpoint(Protocol.HTTP_PROTOBUF, "http://bad host"))
        .isEqualTo("http://bad host");
    assertThat(ZipkinTracing.resolveOtlpEndpoint(Protocol.GRPC, "http://c:4317"))
        .isEqualTo("http://c:4317");
  }

  @Test
  void resolveAppliesZipkinDefaults() {
    TracingConfig c = ZipkinTracing.resolveConfig(cfg().build(), Map.of(), null);
    assertThat(c.protocol()).isEqualTo(Protocol.NATIVE);
    assertThat(c.propagators()).containsExactly("b3", "tracecontext", "baggage");
    assertThat(c.endpoint()).isEmpty();
    assertThat(c.compression()).isEqualTo(Compression.GZIP);
    TracingConfig explicit =
        ZipkinTracing.resolveConfig(
            cfg()
                .protocol(Protocol.GRPC)
                .propagators(List.of("tracecontext"))
                .endpoint("http://c:4317")
                .compression(Compression.NONE)
                .sampler(SamplerType.ALWAYS_OFF)
                .resourceAttributes(Map.of("a", "1"))
                .build(),
            env(
                P + "PROTOCOL",
                "native",
                "OTEL_PROPAGATORS",
                "b3multi",
                P + "COMPRESSION",
                "gzip",
                "OTEL_TRACES_SAMPLER",
                "always_on",
                "OTEL_RESOURCE_ATTRIBUTES",
                "b=2"),
            null);
    assertThat(explicit.protocol()).isEqualTo(Protocol.GRPC);
    assertThat(explicit.propagators()).containsExactly("tracecontext");
    assertThat(explicit.endpoint()).contains("http://c:4317");
    assertThat(explicit.compression()).isEqualTo(Compression.NONE);
    assertThat(explicit.sampler()).isEqualTo(SamplerType.ALWAYS_OFF);
    assertThat(explicit.resourceAttributes()).containsExactly(Map.entry("a", "1"));
    assertThat(
            ZipkinTracing.resolveConfig(
                    cfg().build(), env(P + "PROTOCOL", "grpc"), Protocol.HTTP_PROTOBUF)
                .protocol())
        .isEqualTo(Protocol.HTTP_PROTOBUF);
  }

  @Test
  void resolveReadsTheEnvironment() {
    Map<String, String> e =
        env(
            P + "PROTOCOL",
            "NATIVE",
            "ZIPKIN_URL",
            "http://z:9411/",
            "OTEL_EXPORTER_ZIPKIN_TIMEOUT",
            "5s",
            P + "COMPRESSION",
            "none",
            "OTEL_RESOURCE_ATTRIBUTES",
            "service.name=x,team=a%20b",
            "OTEL_TRACES_SAMPLER",
            "parentbased_traceidratio",
            "OTEL_TRACES_SAMPLER_ARG",
            "0.25",
            "OTEL_PROPAGATORS",
            "tracecontext, b3multi,,");
    TracingConfig c = ZipkinTracing.resolveConfig(cfg().build(), e, null);
    assertThat(c.protocol()).isEqualTo(Protocol.NATIVE);
    assertThat(c.endpoint()).contains("http://z:9411/api/v2/spans");
    assertThat(c.batch().exportTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(c.compression()).isEqualTo(Compression.NONE);
    assertThat(c.resourceAttributes()).containsExactly(Map.entry("team", "a b"));
    assertThat(c.sampler()).isEqualTo(SamplerType.PARENTBASED_TRACEIDRATIO);
    assertThat(c.samplerRatio()).isEqualTo(0.25);
    assertThat(c.propagators()).containsExactly("b3multi", "tracecontext");
    assertThat(
            ZipkinTracing.resolveConfig(
                    cfg().build(),
                    env(
                        P + "ENDPOINT",
                        "http://e/in",
                        "OTEL_EXPORTER_ZIPKIN_ENDPOINT",
                        "http://o/in"),
                    null)
                .endpoint())
        .contains("http://e/in");
    assertThat(
            ZipkinTracing.resolveConfig(
                    cfg().build(),
                    env("OTEL_TRACES_SAMPLER", "always_off", "OTEL_TRACES_SAMPLER_ARG", "x"),
                    null)
                .sampler())
        .isEqualTo(SamplerType.ALWAYS_OFF);
    assertThat(
            ZipkinTracing.resolveConfig(cfg().build(), env(P + "COMPRESSION", "GZIP"), null)
                .compression())
        .isEqualTo(Compression.GZIP);
    TracingConfig grpc =
        ZipkinTracing.resolveConfig(
            cfg().build(),
            env(P + "PROTOCOL", "grpc", "OTEL_EXPORTER_OTLP_ENDPOINT", "http://c:4317"),
            null);
    assertThat(grpc.protocol()).isEqualTo(Protocol.GRPC);
    assertThat(grpc.endpoint()).contains("http://c:4317");
    TracingConfig http =
        ZipkinTracing.resolveConfig(
            cfg().build(),
            env(P + "PROTOCOL", "http/protobuf", "OTEL_EXPORTER_OTLP_ENDPOINT", "http://c:4318"),
            null);
    assertThat(http.endpoint()).contains("http://c:4318/v1/traces");
    TracingConfig traces =
        ZipkinTracing.resolveConfig(
            cfg().build(),
            env(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://c:4318/t",
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://c:4318"),
            Protocol.HTTP_PROTOBUF);
    assertThat(traces.endpoint()).contains("http://c:4318/t");
    assertThat(ZipkinTracing.resolveConfig(cfg().build(), Map.of(), Protocol.GRPC).endpoint())
        .isEmpty();
  }

  @Test
  void invalidVariablesNameTheVariableNotTheValue() {
    String[][] bad = {
      {P + "PROTOCOL", "carrier-pigeon"},
      {"OTEL_EXPORTER_ZIPKIN_TIMEOUT", "soon"},
      {"OTEL_EXPORTER_ZIPKIN_TIMEOUT", "0"},
      {P + "COMPRESSION", "brotli"},
      {"OTEL_TRACES_SAMPLER", "sometimes"},
      {"OTEL_RESOURCE_ATTRIBUTES", "novalue"},
      {"OTEL_RESOURCE_ATTRIBUTES", "=x"},
      {"OTEL_RESOURCE_ATTRIBUTES", "a=%zz"},
    };
    for (String[] kv : bad) {
      assertThatThrownBy(() -> ZipkinTracing.resolveConfig(cfg().build(), env(kv), null))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining(kv[0])
          .message()
          .doesNotContain("pigeon")
          .doesNotContain("brotli");
    }
    for (String arg : new String[] {"2", "abc"}) {
      assertThatThrownBy(
              () ->
                  ZipkinTracing.resolveConfig(
                      cfg().build(),
                      env("OTEL_TRACES_SAMPLER", "traceidratio", "OTEL_TRACES_SAMPLER_ARG", arg),
                      null))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("OTEL_TRACES_SAMPLER_ARG");
    }
  }

  @Test
  void tracingConfigFromEnvFindsTheServiceName() {
    assertThat(
            ZipkinTracing.tracingConfigFromEnv(null, env("OTEL_SERVICE_NAME", "svc")).serviceName())
        .isEqualTo("svc");
    assertThat(
            ZipkinTracing.tracingConfigFromEnv(
                    " ", env("OTEL_RESOURCE_ATTRIBUTES", "service.name=r"))
                .serviceName())
        .isEqualTo("r");
    assertThat(ZipkinTracing.tracingConfigFromEnv("given", Map.of()).serviceName())
        .isEqualTo("given");
    assertThatThrownBy(
            () -> ZipkinTracing.tracingConfigFromEnv(null, env("OTEL_RESOURCE_ATTRIBUTES", "a=b")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("OTEL_SERVICE_NAME");
    assertThat(ZipkinTracing.serviceNameFromEnv(env("OTEL_RESOURCE_ATTRIBUTES", "service.name= ")))
        .isEmpty();
    assertThat(ZipkinTracing.tracingConfigFromEnv("x").serviceName()).isEqualTo("x");
  }

  @Test
  void exporterFactory() {
    var factory = ZipkinTracing.exporterFactory(null, null);
    SpanExporter nativeExporter = factory.create(cfg().protocol(Protocol.NATIVE).build());
    assertThat(nativeExporter).isInstanceOf(ZipkinSpanExporter.class);
    assertThat(((ZipkinSpanExporter) nativeExporter).reporter().endpoint())
        .isEqualTo("http://127.0.0.1:9411/api/v2/spans");
    nativeExporter.shutdown();
    SpanExporter withTls =
        factory.create(
            cfg()
                .protocol(Protocol.NATIVE)
                .endpoint("https://z/api/v2/spans")
                .tls(TlsOptions.defaults())
                .header("X-K", "v")
                .build());
    assertThat(((ZipkinSpanExporter) withTls).reporter().endpoint())
        .isEqualTo("https://z/api/v2/spans");
    withTls.shutdown();
    assertThatThrownBy(() -> factory.create(cfg().protocol(Protocol.GRPC).build()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("Collector");
    assertThatThrownBy(
            () ->
                factory.create(
                    cfg()
                        .protocol(Protocol.HTTP_PROTOBUF)
                        .endpoint("http://z:9411/api/v2/spans/")
                        .build()))
        .isInstanceOf(ConfigException.class);
    SpanExporter grpc =
        factory.create(cfg().protocol(Protocol.GRPC).endpoint("http://c:4317").build());
    assertThat(grpc.getClass().getSimpleName()).contains("Grpc");
    grpc.shutdown().join(5, TimeUnit.SECONDS);
    SpanExporter http =
        factory.create(cfg().protocol(Protocol.HTTP_PROTOBUF).endpoint("http://c:4318").build());
    assertThat(http.toString()).contains("http://c:4318/v1/traces");
    http.shutdown().join(5, TimeUnit.SECONDS);
  }

  // -------------------------------------------------------------------------------------------
  // end to end against a mock Zipkin

  @Test
  void initTracingExportsMappedSpans() throws Exception {
    ZipkinConfig clientConfig =
        ZipkinConfig.builder().url(server.url()).header("Authorization", "Bearer gw-t0k3n").build();
    try (ZipkinTracingHandle h =
        ZipkinTracing.initTracing(
            cfg().serviceVersion("1.0").compression(Compression.NONE).build(),
            ZipkinTracing.Options.builder().env(Map.of()).clientConfig(clientConfig))) {
      assertThat(h.endpoint()).isEqualTo(server.url() + "/api/v2/spans");
      assertThat(h.handle()).isNotNull();
      assertThat(h.openTelemetry()).isNotNull();
      assertThat(h.tracerProvider()).isNotNull();
      assertThat(h.config().protocol()).isEqualTo(Protocol.NATIVE);
      assertThat(h.toString()).contains("native");
      Tracer tracer = h.tracer("orders-lib", "2.1");
      Span parent = tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER).startSpan();
      Span child =
          tracer
              .spanBuilder("call backend")
              .setParent(Context.current().with(parent))
              .setSpanKind(SpanKind.CLIENT)
              .setAttribute("peer.service", "backend")
              .setAttribute("http.status_code", 503L)
              .setAttribute("ratio", 2.0)
              .setAttribute(AttributeKey.stringArrayKey("list"), List.of("a", "b"))
              .startSpan();
      child.addEvent("retry", Attributes.of(AttributeKey.longKey("n"), 2L));
      child.addEvent("plain");
      child.setStatus(StatusCode.ERROR, "boom");
      child.end();
      parent.setStatus(StatusCode.OK);
      parent.end();
      h.forceFlush();
      h.forceFlush(Duration.ofSeconds(5));
      h.forceFlushAsync(Duration.ofSeconds(5)).join();
      assertThat(h.propagators()).isNotNull();
      assertThat(h.tracer("x")).isNotNull();
    }
    assertThat(server.requests()).isNotEmpty();
    MockServer.Recorded req = server.requests().get(0);
    assertThat(req.header("Content-Encoding")).isNull();
    assertThat(req.header("Authorization")).isEqualTo("Bearer gw-t0k3n");
    List<JsonNode> spans = new ArrayList<>();
    for (MockServer.Recorded r : server.requests()) {
      Json.tree(r.body().getBytes(StandardCharsets.UTF_8)).forEach(spans::add);
    }
    JsonNode client =
        spans.stream()
            .filter(s -> s.path("kind").asText().equals("CLIENT"))
            .findFirst()
            .orElseThrow();
    JsonNode srv =
        spans.stream()
            .filter(s -> s.path("kind").asText().equals("SERVER"))
            .findFirst()
            .orElseThrow();
    assertThat(client.path("parentId").asText()).isEqualTo(srv.path("id").asText());
    assertThat(client.path("localEndpoint").path("serviceName").asText()).isEqualTo("checkout");
    assertThat(client.path("remoteEndpoint").path("serviceName").asText()).isEqualTo("backend");
    JsonNode tags = client.path("tags");
    assertThat(tags.path("http.status_code").asText()).isEqualTo("503");
    assertThat(tags.path("ratio").asText()).isEqualTo("2");
    assertThat(tags.path("list").asText()).isEqualTo("[\"a\",\"b\"]");
    assertThat(tags.path("otel.status_code").asText()).isEqualTo("ERROR");
    assertThat(tags.path("error").asText()).isEqualTo("boom");
    assertThat(tags.path("otel.scope.name").asText()).isEqualTo("orders-lib");
    assertThat(tags.path("otel.scope.version").asText()).isEqualTo("2.1");
    assertThat(tags.has("service.name")).isFalse();
    assertThat(client.path("annotations").get(0).path("value").asText())
        .isEqualTo("{\"retry\":{\"n\":2}}");
    assertThat(client.path("annotations").get(1).path("value").asText()).isEqualTo("plain");
    assertThat(client.path("duration").asLong()).isPositive();
    assertThat(srv.path("tags").path("otel.status_code").asText()).isEqualTo("OK");
    assertThat(srv.has("remoteEndpoint")).isFalse();
    assertThat(srv.has("debug")).isFalse();
  }

  @Test
  void initTracingFromEnvAndIgnoreEnv() {
    Map<String, String> e = env("OTEL_SERVICE_NAME", "from-env", P + "URL", server.url());
    try (ZipkinTracingHandle h = ZipkinTracing.initTracingFromEnv(null, e)) {
      assertThat(h.config().serviceName()).isEqualTo("from-env");
      assertThat(h.endpoint()).isEqualTo(server.url() + "/api/v2/spans");
    }
    try (ZipkinTracingHandle h =
        ZipkinTracing.initTracing(cfg().build(), ZipkinTracing.Options.builder().ignoreEnv())) {
      assertThat(h.endpoint()).isEqualTo("http://127.0.0.1:9411/api/v2/spans");
    }
    try (ZipkinTracingHandle h =
        ZipkinTracing.initTracing(
            cfg().build(),
            ZipkinTracing.Options.builder()
                .protocol(Protocol.HTTP_PROTOBUF)
                .env(env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318")))) {
      assertThat(h.endpoint()).isEqualTo("http://collector:4318/v1/traces");
      assertThat(h.isShutdown()).isFalse();
      h.shutdownAsync().join();
      assertThat(h.isShutdown()).isTrue();
    }
    ZipkinTracingHandle h = ZipkinTracing.initTracing(cfg().build());
    assertThat(h.config().protocol()).isEqualTo(Protocol.NATIVE);
    h.close();
  }

  @Test
  void w3cWinsOverB3OnConflictAndBothAreInjected() {
    try (ZipkinTracingHandle h =
        ZipkinTracing.initTracing(cfg().build(), ZipkinTracing.Options.builder().ignoreEnv())) {
      var propagator = h.propagators().getTextMapPropagator();
      Map<String, String> carrier = new HashMap<>();
      Span span = h.tracer("t").spanBuilder("s").startSpan();
      propagator.inject(Context.current().with(span), carrier, Map::put);
      span.end();
      assertThat(carrier).containsKeys("traceparent", "b3");
      Map<String, String> incoming =
          Map.of(
              "traceparent",
              "00-" + TRACE + "-00000000000000aa-01",
              "b3",
              "463ac35c9f6413ad463ac35c9f6413ad-00000000000000bb-1");
      Context ctx = Spans.extract(incoming, h.propagators());
      assertThat(Span.fromContext(ctx).getSpanContext().getTraceId()).isEqualTo(TRACE);
    }
  }

  // -------------------------------------------------------------------------------------------
  // exporter

  private static SpanData data(SpanKind kind, StatusData status, Resource resource) {
    return TestSpanData.builder()
        .setSpanContext(
            SpanContext.create(
                TRACE, "00000000000000ab", TraceFlags.getSampled(), TraceState.getDefault()))
        .setParentSpanContext(
            SpanContext.create(
                TRACE, "00000000000000aa", TraceFlags.getSampled(), TraceState.getDefault()))
        .setName("op")
        .setKind(kind)
        .setStartEpochNanos(1_795_035_874_000_000_123L)
        .setEndEpochNanos(1_795_035_874_000_000_400L)
        .setHasEnded(true)
        .setStatus(status)
        .setResource(resource)
        .setInstrumentationScopeInfo(InstrumentationScopeInfo.create(""))
        .setAttributes(
            Attributes.of(
                AttributeKey.stringKey("server.address"), "db", AttributeKey.booleanKey("b"), true))
        .setTotalAttributeCount(5)
        .setEvents(List.of(EventData.create(1_000_000L, "e", Attributes.empty())))
        .setTotalRecordedEvents(3)
        .build();
  }

  @Test
  void toZipkinSpanEdgeCases() {
    ZipkinSpan s =
        ZipkinSpanExporter.toZipkinSpan(
            data(SpanKind.PRODUCER, StatusData.unset(), Resource.empty()));
    assertThat(s.kind()).contains(ZipkinKind.PRODUCER);
    assertThat(s.parentId()).contains("00000000000000aa");
    assertThat(s.localEndpoint().get().serviceName()).contains(ZipkinSpanExporter.UNKNOWN_SERVICE);
    assertThat(s.remoteEndpoint().get().serviceName()).contains("db");
    assertThat(s.timestamp()).hasValue(1_795_035_874_000_000L);
    assertThat(s.duration()).hasValue(1L);
    assertThat(s.tags())
        .containsEntry("b", "true")
        .containsEntry("otel.dropped_attributes_count", "3")
        .containsEntry("otel.dropped_events_count", "2")
        .doesNotContainKey("otel.scope.name")
        .doesNotContainKey("otel.status_code");
    assertThat(s.annotations()).containsExactly(new ZipkinAnnotation(1000, "e"));
    ZipkinSpan internal =
        ZipkinSpanExporter.toZipkinSpan(
            data(
                SpanKind.INTERNAL,
                StatusData.error(),
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), ""))));
    assertThat(internal.kind()).isEmpty();
    assertThat(internal.remoteEndpoint()).isEmpty();
    assertThat(internal.tags()).containsEntry("error", "");
    assertThat(internal.localEndpoint().get().serviceName()).contains("unknown_service");
    assertThat(
            ZipkinSpanExporter.toZipkinSpan(
                    data(SpanKind.CONSUMER, StatusData.ok(), Resource.empty()))
                .kind())
        .contains(ZipkinKind.CONSUMER);
    SpanData root =
        TestSpanData.builder()
            .setSpanContext(
                SpanContext.create(
                    TRACE, "00000000000000ab", TraceFlags.getSampled(), TraceState.getDefault()))
            .setName("root")
            .setKind(SpanKind.CLIENT)
            .setStartEpochNanos(1)
            .setEndEpochNanos(5_000_600)
            .setHasEnded(true)
            .setStatus(StatusData.unset())
            .setInstrumentationScopeInfo(
                InstrumentationScopeInfo.builder("lib").setVersion("").build())
            .build();
    ZipkinSpan r = ZipkinSpanExporter.toZipkinSpan(root);
    assertThat(r.parentId()).isEmpty();
    assertThat(r.remoteEndpoint()).isEmpty();
    assertThat(r.duration()).hasValue(5001L);
    assertThat(r.tags())
        .containsEntry("otel.scope.name", "lib")
        .doesNotContainKey("otel.scope.version");
  }

  @Test
  void exporterLifecycleAndErrors() {
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    server.fallback(ex -> MockServer.send(ex, 400, "bad"));
    ZipkinConfig config =
        ZipkinConfig.builder().url(server.url()).retry(RetryPolicy.none()).build();
    ZipkinSpanExporter exporter =
        ZipkinSpanExporter.builder()
            .config(config)
            .compression(Compression.NONE)
            .maxBatchSize(10)
            .onError(
                e -> {
                  errors.add(e);
                  throw new IllegalStateException("callback failure is ignored");
                })
            .build();
    assertThat(exporter.flush().isSuccess()).isTrue();
    CompletableResultCode result =
        exporter.export(List.of(data(SpanKind.SERVER, StatusData.ok(), Resource.empty())));
    result.join(5, TimeUnit.SECONDS);
    assertThat(result.isSuccess()).isFalse();
    assertThat(errors).hasSize(1);
    assertThat(exporter.toString()).contains("ZipkinReporter");
    assertThat(exporter.shutdown().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
    assertThat(exporter.shutdown().isSuccess()).isTrue();
    assertThat(exporter.export(List.of()).isSuccess()).isFalse();

    // a shared reporter stays open; a mapping failure fails the batch
    server.fallback(ex -> MockServer.send(ex, 202, ""));
    try (var reporter = ZipkinReporter.create(config)) {
      ZipkinSpanExporter shared = ZipkinSpanExporter.create(reporter);
      CompletableResultCode ok =
          shared.export(List.of(data(SpanKind.SERVER, StatusData.ok(), Resource.empty())));
      shared.flush().join(5, TimeUnit.SECONDS);
      assertThat(ok.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
      List<SpanData> broken = new ArrayList<>();
      broken.add(null);
      assertThat(shared.export(broken).isSuccess()).isFalse();
      shared.shutdown().join(5, TimeUnit.SECONDS);
      reporter.report(List.of());
    }
    ZipkinSpanExporter quiet = ZipkinSpanExporter.builder().config(config).build();
    List<SpanData> broken = new ArrayList<>();
    broken.add(null);
    assertThat(quiet.export(broken).isSuccess()).isFalse();
    quiet.shutdown();
    ZipkinSpanExporter fromEnv = ZipkinSpanExporter.builder().build();
    assertThat(fromEnv.reporter().compression()).isEqualTo(Compression.GZIP);
    fromEnv.shutdown();
    assertThat(new String(new byte[0], StandardCharsets.UTF_8)).isEmpty();
  }
}
