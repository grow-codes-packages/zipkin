package codes.grow.packages.zipkin.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.ZipkinConfig;
import codes.grow.packages.zipkin.ZipkinDependencyLink;
import codes.grow.packages.zipkin.ZipkinEndpoint;
import codes.grow.packages.zipkin.ZipkinKind;
import codes.grow.packages.zipkin.ZipkinModel;
import codes.grow.packages.zipkin.ZipkinQueryClient;
import codes.grow.packages.zipkin.ZipkinReporter;
import codes.grow.packages.zipkin.ZipkinServerInfo;
import codes.grow.packages.zipkin.ZipkinSpan;
import codes.grow.packages.zipkin.ZipkinSpanExporter;
import codes.grow.packages.zipkin.ZipkinTraceQuery;
import codes.grow.packages.zipkin.ZipkinTracing;
import codes.grow.packages.zipkin.ZipkinTracingHandle;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.core.HealthStatus;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.Spans;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import codes.grow.packages.zipkin.tracing.query.DependencyLink;
import codes.grow.packages.zipkin.tracing.query.Span;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceSearchQuery;
import codes.grow.packages.zipkin.tracing.query.TraceSummary;
import codes.grow.packages.zipkin.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Every public function of the Zipkin package against the real Zipkin 3.6.1 from {@code
 * docker/compose.yml}: writes go through the reporter or the tracing exporter and are read back
 * through the query API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZipkinIntegrationTest {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Map<String, String> env;
  private ZipkinClient client;
  private ZipkinQueryClient query;
  private final String tag = "java-" + hex(3);
  private final String frontend = "sdk-java-fe-" + tag;
  private final String backend = "sdk-java-be-" + tag;
  private final String lonely = "sdk-java-gz-" + tag;
  private final String salty = "sdk-java-salt-" + tag;
  private final String traceId = hex(16);
  private final String trace64 = hex(8);
  private final String saltTrace = hex(16);
  private final String rootId = hex(8);
  private final String clientId = hex(8);
  private final String serverId = hex(8);
  private final long nowMicros = System.currentTimeMillis() * 1000;

  private static String hex(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    b[0] |= 0x10; // never all zero
    return HexFormat.of().formatHex(b);
  }

  @BeforeAll
  void setUp() throws Exception {
    env = ConformanceTest.environment();
    String urlVariable = EnvReader.DEFAULT_PREFIX + "URL";
    assumeTrue(env.containsKey(urlVariable), urlVariable + " is not set; use docker/compose.yml");
    client = ZipkinClient.builder().fromEnv(EnvReader.of(env)).build();
    query = client.query();
    // frontend SERVER root → frontend CLIENT → backend SERVER (error), as JSON
    client
        .reporter()
        .report(
            List.of(
                ZipkinSpan.builder(traceId, rootId)
                    .kind(ZipkinKind.SERVER)
                    .name("GET /API/Orders")
                    .timestamp(nowMicros)
                    .duration(150_000)
                    .localEndpoint(ZipkinEndpoint.of(frontend, "10.0.0.1", null, 8080))
                    .annotation(nowMicros + 1000, "cache.miss")
                    .tag("grow.test", tag)
                    .tag("http.method", "GET")
                    .build(),
                ZipkinSpan.builder(traceId, clientId)
                    .parentId(rootId)
                    .kind("client")
                    .name("get /orders")
                    .timestamp(nowMicros + 5000)
                    .duration(100_000)
                    .localServiceName(frontend)
                    .remoteEndpoint(ZipkinEndpoint.of(backend, null, null, 9000))
                    .tag("grow.test", tag)
                    .tag("http.method", "GET")
                    .build(),
                ZipkinSpan.builder(traceId, serverId)
                    .parentId(clientId)
                    .kind(ZipkinKind.SERVER)
                    .name("get /orders")
                    .timestamp(nowMicros + 10_000)
                    .duration(80_000)
                    .localEndpoint(ZipkinEndpoint.of(backend, null, null, 9000))
                    .tag("error", "boom")
                    .tag("grow.test", tag)
                    .build()));
    // a 64-bit trace with upper-case ids and non-string tags, gzip-compressed, async
    try (ZipkinReporter gzip =
        client.createReporter(ZipkinReporter.Options.defaults().compression(Compression.GZIP))) {
      gzip.reportAsync(
              List.of(
                  ZipkinSpan.builder(trace64.toUpperCase(Locale.ROOT), hex(8).toUpperCase(Locale.ROOT))
                      .name("lonely-op")
                      .timestamp(Instant.now())
                      .duration(Duration.ofMillis(5))
                      .localServiceName(lonely)
                      .tag("grow.test", tag)
                      .tag("flag", true)
                      .tag("n", 7)
                      .build()))
          .get(30, TimeUnit.SECONDS);
    }
    // a tag value Zipkin's annotationQuery cannot express, through a standalone reporter
    try (ZipkinReporter own = ZipkinReporter.create(ZipkinConfig.fromEnv(env))) {
      own.report(
          List.of(
              ZipkinSpan.builder(saltTrace, hex(8))
                  .name("season")
                  .timestamp(nowMicros)
                  .duration(1000)
                  .localServiceName(salty)
                  .tag("note", "salt and pepper")
                  .build()));
    }
  }

  @AfterAll
  void tearDown() {
    if (client != null) {
      client.close();
      client.close();
      assertTrue(client.isClosed());
    }
  }

  // -------------------------------------------------------------------------------------------
  // client

  @Test
  void clientHealthPingVersion() throws Exception {
    HealthStatus health = client.health();
    assertTrue(health.healthy(), health.toString());
    assertEquals("UP", health.status());
    assertTrue(health.details().getOrDefault("storage", "").contains("InMemoryStorage"));
    assertTrue(client.healthAsync().get().healthy());
    assertFalse(client.ping().isNegative());
    assertFalse(client.pingAsync().get().isNegative());
    String expected = env.getOrDefault("ZIPKIN_VERSION", "3.6.1");
    assertEquals(expected, client.serverVersion());
    assertEquals(expected, client.serverVersionAsync().get());
    ZipkinServerInfo info = client.serverInfo();
    assertEquals(Optional.of(expected), info.version());
    assertTrue(info.commit().isPresent());
    assertEquals(info, client.serverInfoAsync().get());
  }

  @Test
  void fromEnvDerivesTheEndpointAndPassesGatewayHeaders() {
    ZipkinConfig cfg = ZipkinConfig.fromEnv(env);
    assertTrue(cfg.endpoint().endsWith("/api/v2/spans"));
    try (ZipkinClient gw =
        ZipkinClient.builder()
            .fromEnv(EnvReader.of(env))
            .header("Authorization", "Bearer integration-secret")
            .build()) {
      assertTrue(gw.query().listServices().contains(frontend));
      assertFalse(gw.toString().contains("integration-secret"));
    }
  }

  // -------------------------------------------------------------------------------------------
  // reporter → query

  @Test
  void getTraceSpansReturnsTheReportedSpans() throws Exception {
    List<ZipkinSpan> spans = query.getTraceSpans(traceId).orElseThrow();
    List<String> names = new ArrayList<>();
    spans.forEach(s -> names.add(s.name().orElse("")));
    names.sort(null);
    assertEquals(List.of("get /api/orders", "get /orders", "get /orders"), names);
    ZipkinSpan root = spans.stream().filter(s -> s.id().equals(rootId)).findFirst().orElseThrow();
    assertEquals(Optional.of(ZipkinKind.SERVER), root.kind());
    assertEquals(nowMicros + 1000, root.annotations().get(0).timestamp());
    assertEquals(
        Optional.of(backend),
        spans.stream()
            .filter(s -> s.id().equals(clientId))
            .findFirst()
            .flatMap(ZipkinSpan::remoteEndpoint)
            .flatMap(ZipkinEndpoint::serviceName));
    assertEquals(3, query.getTraceSpansAsync(traceId.toUpperCase(Locale.ROOT)).get().orElseThrow().size());
  }

  @Test
  void gzipUpperCaseIdsAndNonStringTagsAreNormalized() {
    List<ZipkinSpan> spans = query.getTraceSpans(trace64).orElseThrow();
    assertEquals(trace64, spans.get(0).traceId());
    assertEquals("true", spans.get(0).tags().get("flag"));
    assertEquals("7", spans.get(0).tags().get("n"));
    assertEquals(1, query.getTraceSpans("0000000000000000" + trace64).orElseThrow().size());
  }

  @Test
  void getTraceReturnsTheNormalizedModel() throws Exception {
    Trace trace = query.getTrace(traceId).orElseThrow();
    assertEquals(traceId, trace.traceId());
    assertEquals(3, trace.spans().size());
    Span root = trace.root().orElseThrow();
    assertEquals("get /api/orders", root.name());
    assertEquals(Span.Kind.SERVER, root.kind());
    assertEquals(frontend, root.serviceName());
    assertEquals("cache.miss", root.events().get(0).name());
    Span failed = trace.spans().stream().filter(s -> s.spanId().equals(serverId)).findFirst().orElseThrow();
    assertEquals(Span.StatusCode.ERROR, failed.status().code());
    assertEquals(Optional.of("boom"), failed.status().message());
    assertEquals("0000000000000000" + trace64, query.getTraceAsync(trace64).get().orElseThrow().traceId());
    assertTrue(query.getTrace(hex(16)).isEmpty());
    assertThrows(ValidationException.class, () -> query.getTrace("not-hex"));
    assertThrows(
        ValidationException.class,
        () -> client.reporter().report(List.of(ZipkinSpan.builder("zz", "1").build())));
  }

  @Test
  void waitForTraceResolves() throws Exception {
    Trace t = query.waitForTrace(traceId, new WaitForTraceOptions(Duration.ofSeconds(30), Duration.ofMillis(200), 3));
    assertEquals(3, t.spans().size());
    assertEquals(3, query.waitForTraceAsync(traceId, WaitForTraceOptions.defaults()).get().spans().size());
    assertEquals(3, query.waitForTrace(traceId).spans().size());
  }

  @Test
  void getTracesWithEveryFilter() throws Exception {
    List<List<ZipkinSpan>> traces =
        query.getTraces(
            ZipkinTraceQuery.builder()
                .serviceName(frontend.toUpperCase(Locale.ROOT))
                .remoteServiceName(backend)
                .spanName("get /orders")
                .annotationQuery("http.method=GET")
                .tag("grow.test", tag)
                .annotation("cache.miss")
                .minDurationMicros(50_000)
                .maxDurationMicros(500_000)
                .endTs(Instant.now().plusSeconds(60))
                .lookback(Duration.ofHours(1))
                .limit(5)
                .build());
    assertEquals(1, traces.size());
    assertEquals(traceId, traces.get(0).get(0).traceId());
    assertTrue(
        query.getTracesAsync(ZipkinTraceQuery.builder().serviceName(frontend).minDurationMicros(10_000_000).build())
            .get()
            .isEmpty());
    assertFalse(query.getTraces().isEmpty());
    assertThrows(
        ValidationException.class,
        () -> query.getTraces(ZipkinTraceQuery.builder().maxDurationMicros(5).build()));
  }

  @Test
  void tagValuesWithAndAreMatchedClientSide() {
    List<List<ZipkinSpan>> hit =
        query.getTraces(ZipkinTraceQuery.builder().serviceName(salty).tag("note", "salt and pepper").build());
    assertEquals(1, hit.size());
    assertTrue(
        query.getTraces(ZipkinTraceQuery.builder().serviceName(salty).tag("note", "salt and sugar").build())
            .isEmpty());
    assertEquals(
        Optional.of("grow.test=" + tag + " and error"),
        ZipkinQueryClient.buildAnnotationQuery(Map.of("grow.test", tag), List.of("error")));
  }

  @Test
  void getTraceManyKeepsRequestOrder() throws Exception {
    String missing = hex(16);
    List<List<ZipkinSpan>> traces =
        query.getTraceMany(List.of(trace64.toUpperCase(Locale.ROOT), missing, traceId, "0000000000000000" + trace64));
    assertEquals(2, traces.size());
    assertEquals(trace64, traces.get(0).get(0).traceId());
    assertEquals(traceId, traces.get(1).get(0).traceId());
    assertTrue(query.getTraceMany(List.of()).isEmpty());
    assertEquals(1, query.getTraceManyAsync(List.of(traceId)).get().size());
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      many.add(hex(16));
    }
    many.add(saltTrace);
    assertEquals(1, query.getTraceMany(many).size()); // 2 requests: 100 + 31 ids
  }

  @Test
  void searchTracesSummarises() throws Exception {
    List<TraceSummary> found =
        query.searchTraces(
            TraceSearchQuery.builder().service(frontend).tag("grow.test", tag).limit(5).build());
    assertEquals(1, found.size());
    TraceSummary s = found.get(0);
    assertEquals(traceId, s.traceId());
    assertEquals(Optional.of(frontend), s.rootServiceName());
    assertEquals(Optional.of("get /api/orders"), s.rootName());
    assertEquals(3, s.spanCount());
    assertEquals(150_000, s.durationMicros());
    Instant end = Instant.now().plusSeconds(5);
    assertEquals(
        1,
        query.searchTracesAsync(
                TraceSearchQuery.builder()
                    .service(backend)
                    .operation("get /orders")
                    .start(end.minus(Duration.ofMinutes(30)))
                    .end(end)
                    .build())
            .get()
            .size());
  }

  @Test
  void namesAndRemoteServices() throws Exception {
    assertTrue(query.listServices().contains(frontend));
    assertTrue(query.listServicesAsync().get().contains(lonely));
    assertEquals(List.of("get /api/orders", "get /orders"), query.listSpans(frontend));
    assertEquals(List.of("get /api/orders", "get /orders"), query.listSpansAsync(frontend).get());
    assertEquals(List.of("get /orders"), query.listOperations(backend));
    assertEquals(List.of("get /orders"), query.listOperationsAsync(backend).get());
    assertEquals(List.of(backend), query.listRemoteServices(frontend));
    assertEquals(List.of(backend), query.listRemoteServicesAsync(frontend).get());
    assertTrue(query.listSpans("nobody-" + tag).isEmpty());
  }

  @Test
  void dependenciesReportTheEdgeWithItsError() throws Exception {
    ZipkinDependencyLink edge = new ZipkinDependencyLink(frontend, backend, 1, 1);
    assertTrue(query.getDependencies(Instant.now().plusSeconds(60), Duration.ofHours(1)).contains(edge));
    assertTrue(query.getDependenciesAsync(null, Duration.ofHours(1)).get().contains(edge));
    assertTrue(query.getDependencies().contains(edge));
    Instant end = Instant.now().plusSeconds(60);
    List<DependencyLink> spec = query.getDependencies(end.minus(Duration.ofHours(1)), end);
    assertTrue(spec.contains(new DependencyLink(frontend, backend, 1)));
    assertTrue(query.getDependenciesAsync((Instant) null, (Instant) null).get().contains(edge.toDependencyLink()));
  }

  @Test
  void autocomplete() throws Exception {
    List<String> keys = query.autocompleteKeys();
    assertTrue(keys.contains("grow.test"), keys.toString());
    assertEquals(keys, query.autocompleteKeysAsync().get());
    assertTrue(query.autocompleteValues("grow.test").contains(tag));
    assertTrue(query.autocompleteValuesAsync("http.method").get().contains("GET"));
    assertTrue(query.autocompleteValues("not-indexed").isEmpty());
  }

  @Test
  void standaloneQueryClient() {
    try (ZipkinQueryClient own = ZipkinQueryClient.create(ZipkinConfig.fromEnv(env))) {
      assertTrue(own.listServices().contains(frontend));
    }
  }

  // -------------------------------------------------------------------------------------------
  // tracing

  private TracingConfig.Builder tracingConfig(String service) {
    return TracingConfig.builder(service)
        .setGlobal(false)
        .batch(new TracingConfig.BatchSettings(2048, 512, Duration.ofMillis(100), Duration.ofSeconds(30)));
  }

  @Test
  void initTracingHelpersPropagationAndMapping() {
    String app = "sdk-java-otel-" + tag;
    String id;
    Map<String, String> carrier = new HashMap<>();
    Map<String, String> fields;
    try (ZipkinTracingHandle h =
        client.initTracing(tracingConfig(app).build(), ZipkinTracing.Options.builder().env(env))) {
      assertTrue(h.endpoint().endsWith("/api/v2/spans"));
      Tracer tracer = h.tracer("orders-lib", "2.1");
      io.opentelemetry.api.trace.Span parent =
          tracer.spanBuilder("GET /checkout").setSpanKind(SpanKind.SERVER).startSpan();
      Scope scope = parent.makeCurrent();
      try {
        id = Spans.currentTraceId().orElseThrow();
        fields = Spans.traceFields();
        Spans.addEvent("checkpoint", Map.of("step", 1L));
        Spans.inject(Context.current(), carrier, h.propagators());
        io.opentelemetry.api.trace.Span call =
            tracer
                .spanBuilder("call payments")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("peer.service", "payments-" + tag)
                .setAttribute("retries", 2L)
                .startSpan();
        call.setStatus(StatusCode.ERROR, "declined");
        call.end();
        Spans.withSpan("inner-work", () -> Spans.setError("inner failed"));
      } finally {
        scope.close();
      }
      parent.end();
      h.forceFlush();
    }
    assertEquals(id, fields.get("trace_id"));
    assertTrue(carrier.containsKey("traceparent"));
    assertTrue(carrier.get("b3").startsWith(id));
    try (ZipkinTracingHandle other =
        ZipkinTracing.initTracing(
            tracingConfig("sdk-java-extract-" + tag).build(),
            ZipkinTracing.Options.builder().ignoreEnv())) {
      Context extracted = Spans.extract(Map.of("b3", carrier.get("b3")), other.propagators());
      assertEquals(
          id, io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext().getTraceId());
    }

    query.waitForTrace(
        id, new WaitForTraceOptions(Duration.ofSeconds(30), Duration.ofMillis(200), 3));
    List<ZipkinSpan> spans = query.getTraceSpans(id).orElseThrow();
    assertEquals(3, spans.size());
    ZipkinSpan call = spans.stream().filter(s -> s.kind().equals(Optional.of(ZipkinKind.CLIENT))).findFirst().orElseThrow();
    assertEquals(Optional.of(app), call.localEndpoint().flatMap(ZipkinEndpoint::serviceName));
    assertEquals(Optional.of("payments-" + tag), call.remoteEndpoint().flatMap(ZipkinEndpoint::serviceName));
    assertEquals("2", call.tags().get("retries"));
    assertEquals("ERROR", call.tags().get("otel.status_code"));
    assertEquals("declined", call.tags().get("error"));
    assertEquals("orders-lib", call.tags().get("otel.scope.name"));
    ZipkinSpan server = spans.stream().filter(s -> s.kind().equals(Optional.of(ZipkinKind.SERVER))).findFirst().orElseThrow();
    assertEquals("{\"checkpoint\":{\"step\":1}}", server.annotations().get(0).value());
    Trace trace = query.getTrace(id).orElseThrow();
    Span root = trace.root().orElseThrow();
    assertEquals("checkpoint", root.events().get(0).name());
    assertEquals(1, ((Number) root.events().get(0).attributes().get("step")).intValue());
    assertTrue(query.listRemoteServices(app).contains("payments-" + tag));
  }

  @Test
  void presetFromEnvironmentAndStandaloneExporter() throws Exception {
    String app = "sdk-java-env-" + tag;
    Map<String, String> vars = new HashMap<>(env);
    vars.put("OTEL_SERVICE_NAME", app);
    vars.put("OTEL_RESOURCE_ATTRIBUTES", "team=sdk");
    TracingConfig fromEnv = ZipkinTracing.tracingConfigFromEnv(null, vars);
    assertEquals(app, fromEnv.serviceName());
    assertEquals(TracingConfig.Protocol.NATIVE, fromEnv.protocol());
    assertTrue(fromEnv.endpoint().orElseThrow().endsWith("/api/v2/spans"));
    String id;
    try (ZipkinTracingHandle h = ZipkinTracing.initTracingFromEnv(null, vars)) {
      id = Spans.withSpan("env-span", () -> Spans.currentTraceId().orElseThrow());
      assertEquals(app, h.config().serviceName());
    }
    // initTracingFromEnv uses the most recent handle for Spans helpers; it was flushed on close
    assertTrue(query.waitForTrace(id).spans().size() >= 1);
    assertTrue(query.listServices().contains(app));

    // a standalone exporter on the plain OpenTelemetry SDK, read back through toZipkinSpan
    String sdkApp = "sdk-java-exp-" + tag;
    try (ZipkinReporter reporter = ZipkinReporter.create(ZipkinConfig.fromEnv(env))) {
      ZipkinSpanExporter exporter = ZipkinSpanExporter.create(reporter);
      SdkTracerProvider provider =
          SdkTracerProvider.builder()
              .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), sdkApp)))
              .addSpanProcessor(SimpleSpanProcessor.create(exporter))
              .build();
      io.opentelemetry.api.trace.Span s = provider.get("raw").spanBuilder("raw-span").startSpan();
      String rawId = s.getSpanContext().getTraceId();
      s.end();
      CompletableResultCode flushed = provider.forceFlush().join(10, TimeUnit.SECONDS);
      assertTrue(flushed.isSuccess());
      provider.shutdown().join(10, TimeUnit.SECONDS);
      ZipkinSpan stored = query.getTraceSpans(rawId).orElseThrow().get(0);
      assertEquals(Optional.of(sdkApp), stored.localEndpoint().flatMap(ZipkinEndpoint::serviceName));
      assertEquals(Optional.of("raw-span"), stored.name());
      assertNotNull(ZipkinModel.toSpan(stored));
    }
  }
}
