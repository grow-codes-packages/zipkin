package codes.grow.packages.zipkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.core.CancelledException;
import codes.grow.packages.zipkin.core.MockServer;
import codes.grow.packages.zipkin.core.NotFoundException;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.TransportConfig;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.query.DependencyLink;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceSearchQuery;
import codes.grow.packages.zipkin.tracing.query.TraceSummary;
import codes.grow.packages.zipkin.tracing.query.WaitForTraceOptions;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZipkinQueryClientTest {
  private static final String TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private MockServer server;
  private ZipkinQueryClient query;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    ZipkinConfig cfg =
        ZipkinConfig.builder()
            .url(server.url())
            .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
            .build();
    TransportConfig tc = cfg.toTransportConfig();
    query =
        new ZipkinQueryClient(
            new codes.grow.packages.zipkin.core.HttpTransport(tc),
            true,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    query.close();
    server.close();
  }

  private String uri(int i) {
    return URLDecoder.decode(server.requests().get(i).uri(), StandardCharsets.UTF_8);
  }

  /** Path plus query parameters sorted by name (the transport does not keep their order). */
  private static String canonical(String uri) {
    int q = uri.indexOf('?');
    if (q < 0) {
      return uri;
    }
    String[] parts = uri.substring(q + 1).split("&");
    java.util.Arrays.sort(parts);
    return uri.substring(0, q) + "?" + String.join("&", parts);
  }

  private String sorted(int i) {
    return canonical(uri(i));
  }

  @Test
  void getTraceSpansAndTrace() {
    Fixtures.enqueue(server, "getTrace.ok", "getTrace.notFound", "getTrace.ok", "getTrace.id64");
    server.enqueue(200, "[]");
    Optional<List<ZipkinSpan>> spans = query.getTraceSpans(TRACE.toUpperCase());
    assertThat(spans).isPresent();
    assertThat(spans.get()).hasSize(3);
    assertThat(uri(0)).isEqualTo("/api/v2/trace/" + TRACE);
    assertThat(query.getTraceSpans("ffffffffffffffffffffffffffffffff")).isEmpty();
    Trace trace = query.getTraceAsync(TRACE).join().orElseThrow();
    assertThat(trace.spans()).hasSize(3);
    Trace id64 = query.getTrace("463ac35cad1c0df6").orElseThrow();
    assertThat(id64.traceId()).isEqualTo("0000000000000000463ac35cad1c0df6");
    assertThat(query.getTrace(TRACE)).isEmpty();
    assertThatThrownBy(() -> query.getTrace("zz")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.getTraceSpansAsync("zz").join())
        .hasCauseInstanceOf(ValidationException.class);
    assertThat(server.requests()).hasSize(5);
  }

  @Test
  void serverValidationErrorsKeepTheVendorText() {
    Fixtures.enqueue(server, "getTrace.invalid400", "listSpans.missing400");
    server.enqueue(404, "gone", "Content-Type", "text/*");
    assertThatThrownBy(() -> query.getTraceSpans("0000000000000001"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("zz should be lower-hex encoded");
    assertThatThrownBy(() -> query.listSpans("x"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Mandatory parameter/header/attribute is missing: serviceName");
    assertThatThrownBy(() -> query.listServices())
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("not found: gone");
  }

  @Test
  void waitForTracePolls() {
    Fixtures.enqueue(server, "getTrace.notFound", "getTrace.ok");
    Trace t =
        query.waitForTrace(
            TRACE, new WaitForTraceOptions(Duration.ofSeconds(5), Duration.ofMillis(10), 3));
    assertThat(t.spans()).hasSize(3);
  }

  @Test
  void getTracesWithEveryFilter() {
    Fixtures.enqueue(server, "getTraces.allFilters", "getTraces.defaults");
    List<List<ZipkinSpan>> traces =
        query.getTraces(
            ZipkinTraceQuery.builder()
                .serviceName("grow-frontend")
                .remoteServiceName(" ")
                .spanName("get /api/orders")
                .annotationQuery(" http.method=GET ")
                .annotation("cache.miss")
                .minDurationMicros(100_000)
                .maxDurationMicros(200_000)
                .endTs(Instant.ofEpochMilli(1795035940000L))
                .lookback(Duration.ofHours(1))
                .limit(5)
                .build());
    assertThat(traces).hasSize(1);
    assertThat(canonical(server.requests().get(0).uri()))
        .isEqualTo(
            canonical(Fixtures.load("getTraces.allFilters").path("request").path("path").asText()));
    assertThat(query.getTraces()).isNotEmpty();
    assertThat(server.requests().get(1).uri()).isEqualTo("/api/v2/traces");
  }

  @Test
  void termsThatZipkinCannotExpressAreMatchedLocally() {
    String body =
        "[[{\"traceId\":\""
            + TRACE
            + "\",\"id\":\"0000000000000001\",\"tags\":{\"q\":\"a and b\",\"k\":\"v\"},"
            + "\"annotations\":[{\"timestamp\":1,\"value\":\"x and y\"}]}],"
            + "[{\"traceId\":\"463ac35cad1c0df6\",\"id\":\"0000000000000002\",\"tags\":{\"q\":\"other\"}}]]";
    server.enqueue(200, body).enqueue(200, body).enqueue(200, body);
    List<List<ZipkinSpan>> traces =
        query.getTraces(ZipkinTraceQuery.builder().tag("q", "a and b").tag("k", "v").build());
    assertThat(traces).hasSize(1);
    assertThat(uri(0)).isEqualTo("/api/v2/traces?annotationQuery=k=v");
    assertThat(query.getTraces(ZipkinTraceQuery.builder().annotation("x and y").build()))
        .hasSize(1);
    assertThat(
            query.getTraces(
                ZipkinTraceQuery.builder().tags(Map.of("a=b", "c")).annotation("q and z").build()))
        .isEmpty();
    assertThat(uri(2)).isEqualTo("/api/v2/traces");
  }

  @Test
  void getTracesValidatesLocally() {
    List<ZipkinTraceQuery> bad =
        List.of(
            ZipkinTraceQuery.builder().minDurationMicros(-1).build(),
            ZipkinTraceQuery.builder().minDurationMicros(1).maxDurationMicros(-1).build(),
            ZipkinTraceQuery.builder().maxDurationMicros(5).build(),
            ZipkinTraceQuery.builder().minDurationMicros(10).maxDurationMicros(5).build(),
            ZipkinTraceQuery.builder().limit(0).build(),
            ZipkinTraceQuery.builder().lookback(Duration.ZERO).build(),
            ZipkinTraceQuery.builder().endTs(Instant.EPOCH).build(),
            ZipkinTraceQuery.builder().tag("", "v").build(),
            ZipkinTraceQuery.builder().annotation("").build());
    for (ZipkinTraceQuery q : bad) {
      assertThatThrownBy(() -> query.getTraces(q)).isInstanceOf(ValidationException.class);
    }
    assertThatThrownBy(() -> query.getTracesAsync(bad.get(0)).join())
        .isInstanceOf(CompletionException.class);
    assertThat(server.requests()).isEmpty();
    Fixtures.enqueue(server, "getTraces.limitZero400");
    // the server check is also mapped (here forced through a valid local query)
    assertThatThrownBy(() -> query.getTraces(ZipkinTraceQuery.builder().limit(1).build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("limit <= 0");
    ZipkinTraceQuery q =
        ZipkinTraceQuery.builder().tag("t", null).minDurationMicros(1).maxDurationMicros(1).build();
    server.enqueue(200, "[]");
    assertThat(query.getTraces(q)).isEmpty();
    assertThat(sorted(1))
        .isEqualTo("/api/v2/traces?annotationQuery=t=&maxDuration=1&minDuration=1");
    assertThat(q.tags()).containsKey("t");
    assertThat(q.serviceName()).isEmpty();
    assertThat(q.limit()).isEmpty();
  }

  @Test
  void annotationQueryBuilder() {
    assertThat(ZipkinQueryClient.buildAnnotationQuery(Map.of("a", "b"), List.of("error")))
        .contains("a=b and error");
    assertThat(ZipkinQueryClient.buildAnnotationQuery(Map.of(), List.of())).isEmpty();
    java.util.Map<String, String> nullValue = new java.util.HashMap<>();
    nullValue.put("k", null);
    assertThat(ZipkinQueryClient.buildAnnotationQuery(nullValue, List.of())).contains("k=");
    assertThatThrownBy(() -> ZipkinQueryClient.buildAnnotationQuery(Map.of("", "x"), List.of()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> ZipkinQueryClient.buildAnnotationQuery(Map.of("a", "b and c"), List.of()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ZipkinQueryClient.buildAnnotationQuery(Map.of(), List.of("a=b")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void getTraceManyKeepsRequestOrderAndDedupes() {
    Fixtures.enqueue(server, "getTraceMany.ok");
    List<List<ZipkinSpan>> traces =
        query.getTraceMany(
            List.of(
                "463AC35CAD1C0DF6",
                TRACE,
                "0000000000000000463ac35cad1c0df6",
                "ffffffffffffffffffffffffffffffff"));
    assertThat(traces).hasSize(2);
    assertThat(traces.get(0).get(0).traceId()).isEqualTo("463ac35cad1c0df6");
    assertThat(traces.get(1).get(0).traceId()).isEqualTo(TRACE);
    assertThat(uri(0))
        .isEqualTo(
            "/api/v2/traceMany?traceIds=463ac35cad1c0df6,"
                + TRACE
                + ",ffffffffffffffffffffffffffffffff");
    assertThat(query.getTraceMany(List.of())).isEmpty();
    Fixtures.enqueue(server, "getTrace.ok", "getTrace.notFound", "getTraceMany.allMissing");
    assertThat(query.getTraceMany(List.of(TRACE, TRACE.toUpperCase()))).hasSize(1);
    assertThat(uri(1)).isEqualTo("/api/v2/trace/" + TRACE);
    assertThat(query.getTraceManyAsync(List.of(TRACE)).join()).isEmpty();
    assertThat(
            query.getTraceMany(
                List.of("ffffffffffffffffffffffffffffffff", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")))
        .isEmpty();
    assertThatThrownBy(() -> query.getTraceMany(List.of("zz")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void getTraceManyChunksLargeRequests() {
    server.fallback(ex -> MockServer.send(ex, 200, "[]"));
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 201; i++) {
      ids.add(String.format("%032x", i));
    }
    Fixtures.enqueue(server, "getTraceMany.allMissing", "getTraceMany.allMissing");
    server.enqueue(200, "[{\"traceId\":\"" + ids.get(200) + "\",\"id\":\"0000000000000001\"}]");
    List<List<ZipkinSpan>> traces = query.getTraceMany(ids);
    assertThat(traces).hasSize(1);
    assertThat(server.requests()).hasSize(3);
    assertThat(uri(0).split(",")).hasSize(ZipkinQueryClient.TRACE_MANY_CHUNK);
    assertThat(uri(2)).isEqualTo("/api/v2/trace/" + ids.get(200));
  }

  @Test
  void searchTracesSummarisesWithTheDefaultWindow() {
    Fixtures.enqueue(server, "getTraces.allFilters");
    List<TraceSummary> summaries =
        query.searchTraces(
            TraceSearchQuery.builder()
                .service("grow-frontend")
                .operation("get /api/orders")
                .tag("http.method", "GET")
                .minDurationMicros(100L)
                .maxDurationMicros(200_000L)
                .build());
    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).rootServiceName()).contains("grow-frontend");
    assertThat(uri(0))
        .contains("serviceName=grow-frontend")
        .contains("spanName=get /api/orders")
        .contains("annotationQuery=http.method=GET")
        .contains("endTs=" + NOW.toEpochMilli())
        .contains("lookback=3600000")
        .contains("limit=20");
    server.enqueue(200, "[]").enqueue(200, "[]");
    Instant end = Instant.ofEpochMilli(2_000_000L);
    query.searchTracesAsync(TraceSearchQuery.builder().start(end).end(end).limit(3).build()).join();
    assertThat(sorted(1)).isEqualTo("/api/v2/traces?endTs=2000000&limit=3&lookback=1");
    query.searchTraces(TraceSearchQuery.builder().start(end.minusSeconds(60)).end(end).build());
    assertThat(uri(2)).contains("lookback=60000");
    assertThatThrownBy(
            () -> query.searchTraces(TraceSearchQuery.builder().end(Instant.EPOCH).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                query.searchTraces(
                    TraceSearchQuery.builder().start(Instant.EPOCH).end(end).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void searchStartAfterEndIsRejected() throws Exception {
    // TraceSearchQuery itself rejects end < start; the client re-checks it for raw instants
    var start = Instant.ofEpochMilli(5_000);
    var end = Instant.ofEpochMilli(4_000);
    var b = TraceSearchQuery.builder().start(start);
    var f = TraceSearchQuery.class.getDeclaredField("end");
    f.setAccessible(true);
    TraceSearchQuery q = b.build();
    f.set(q, end);
    assertThatThrownBy(() -> query.searchTraces(q))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("start must not be after end");
  }

  @Test
  void names() {
    Fixtures.enqueue(
        server,
        "listServices.ok",
        "listSpans.ok",
        "listSpans.ok",
        "listRemoteServices.ok",
        "listSpans.unknownService");
    assertThat(query.listServicesAsync().join()).isSorted().contains("grow-frontend");
    assertThat(query.listSpans("grow-frontend")).containsExactly("get /api/orders", "get /orders");
    assertThat(uri(1)).isEqualTo("/api/v2/spans?serviceName=grow-frontend");
    assertThat(query.listOperationsAsync("grow-frontend").join()).hasSize(2);
    assertThat(query.listRemoteServices("grow-frontend")).containsExactly("grow-backend");
    assertThat(uri(3)).isEqualTo("/api/v2/remoteServices?serviceName=grow-frontend");
    assertThat(query.listOperations("nobody")).isEmpty();
    assertThatThrownBy(() -> query.listSpans(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.listRemoteServices(null))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).hasSize(5);
  }

  @Test
  void dependencies() {
    Fixtures.enqueue(server, "getDependencies.ok", "getDependencies.ok", "getDependencies.ok");
    Fixtures.enqueue(server, "getDependencies.missingEndTs400");
    List<ZipkinDependencyLink> links =
        query.getDependencies(Instant.ofEpochMilli(1795035940000L), Duration.ofHours(1));
    assertThat(links.get(0))
        .isEqualTo(new ZipkinDependencyLink("grow-frontend", "grow-backend", 1, 1));
    assertThat(sorted(0)).isEqualTo("/api/v2/dependencies?endTs=1795035940000&lookback=3600000");
    List<DependencyLink> spec =
        query.getDependencies(Instant.ofEpochMilli(1_000_000L), Instant.ofEpochMilli(4_600_000L));
    assertThat(spec).contains(new DependencyLink("grow-frontend", "grow-backend", 1));
    assertThat(sorted(1)).isEqualTo("/api/v2/dependencies?endTs=4600000&lookback=3600000");
    assertThat(query.getDependenciesAsync((Instant) null, (Instant) null).join()).hasSize(2);
    assertThat(uri(2)).isEqualTo("/api/v2/dependencies?endTs=" + NOW.toEpochMilli());
    assertThatThrownBy(() -> query.getDependencies())
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("missing: endTs");
    assertThatThrownBy(() -> query.getDependencies(NOW, Duration.ZERO))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.getDependencies(NOW, NOW.minusSeconds(1)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.getDependencies(Instant.EPOCH, (Duration) null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void autocomplete() {
    Fixtures.enqueue(
        server,
        "autocompleteKeys.ok",
        "autocompleteValues.ok",
        "autocompleteValues.notAllowlisted");
    assertThat(query.autocompleteKeys()).containsExactly("grow.test", "http.method");
    assertThat(query.autocompleteValues("grow.test")).contains("run-a").isSorted();
    assertThat(uri(1)).isEqualTo("/api/v2/autocompleteValues?key=grow.test");
    assertThat(query.autocompleteValuesAsync("nope").join()).isEmpty();
    assertThatThrownBy(() -> query.autocompleteValues("")).isInstanceOf(ValidationException.class);
  }

  @Test
  void standaloneLifecycle() {
    ZipkinQueryClient own =
        ZipkinQueryClient.create(ZipkinConfig.builder().url(server.url()).build());
    assertThat(own.toString()).contains(server.url());
    own.close();
    own.close();
    assertThatThrownBy(own::listServices).isInstanceOf(CancelledException.class);
    try (var env = ZipkinQueryClient.fromEnv()) {
      assertThat(env).isNotNull();
    }
  }
}
