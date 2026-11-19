package codes.grow.packages.zipkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.core.CancelledException;
import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.Hooks;
import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.MockServer;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.UnavailableException;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZipkinReporterTest {
  private static final String TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c";
  private MockServer server;
  private final List<Hooks.ErrorInfo> errors = new CopyOnWriteArrayList<>();
  private final List<byte[]> rawBodies = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private ZipkinConfig config() {
    return ZipkinConfig.builder()
        .url(server.url())
        .header("Authorization", "Bearer gw-secret-1")
        .retry(RetryPolicy.builder().maxRetries(2).initialBackoff(Duration.ofMillis(1)).build())
        .hooks(
            new Hooks() {
              @Override
              public void onError(ErrorInfo info) {
                errors.add(info);
              }
            })
        .build();
  }

  /** The recorded request of the real ingest check, rebuilt with the builder. */
  private static List<ZipkinSpan> recordedSpans() {
    JsonNode body = Fixtures.requestBody("report.json");
    return ZipkinModel.spansFromBody(body);
  }

  @Test
  void reportSendsTheRecordedJson() {
    Fixtures.enqueue(server, "report.json");
    try (var r = ZipkinReporter.create(config())) {
      assertThat(r.endpoint()).isEqualTo(server.url() + "/api/v2/spans");
      assertThat(r.compression()).isEqualTo(Compression.NONE);
      assertThat(r.maxBatchSize()).isEqualTo(512);
      r.report(recordedSpans());
    }
    MockServer.Recorded req = server.requests().get(0);
    assertThat(req.method()).isEqualTo("POST");
    assertThat(req.uri()).isEqualTo("/api/v2/spans");
    assertThat(req.header("Content-Type")).isEqualTo("application/json");
    assertThat(req.header("Authorization")).isEqualTo("Bearer gw-secret-1");
    JsonNode sent = Json.tree(req.body().getBytes(StandardCharsets.UTF_8));
    JsonNode expected = Fixtures.requestBody("report.json");
    assertThat(sent.size()).isEqualTo(expected.size());
    for (int i = 0; i < sent.size(); i++) {
      assertThat(sent.get(i)).isEqualTo(expected.get(i));
    }
  }

  @Test
  void gzipBatchingAndEmptyReports() throws Exception {
    server.fallback(
        ex -> {
          rawBodies.add(new byte[0]);
          MockServer.send(ex, 202, "");
        });
    List<ZipkinSpan> spans = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      spans.add(
          ZipkinSpan.builder(TRACE.toUpperCase(), String.format("%016X", i))
              .kind("server")
              .tag("n", i)
              .build());
    }
    try (var r =
        ZipkinReporter.create(
            config(),
            ZipkinReporter.Options.defaults()
                .compression(Compression.GZIP)
                .maxBatchSize(2)
                .endpoint(server.url() + "/custom"))) {
      r.report(List.of());
      assertThat(server.requests()).isEmpty();
      r.reportAsync(spans).join();
      assertThat(r.toString()).contains("gzip").contains("maxBatchSize=2");
    }
    assertThat(server.requests()).hasSize(3);
    assertThat(server.requests().get(0).uri()).isEqualTo("/custom");
    assertThat(server.requests().get(0).header("Content-Encoding")).isEqualTo("gzip");
    byte[] gz = ZipkinReporter.gzip("[1]".getBytes(StandardCharsets.UTF_8));
    try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("[1]");
    }
  }

  @Test
  void plainBatchesAreNormalized() {
    server.fallback(ex -> MockServer.send(ex, 202, ""));
    try (var r =
        ZipkinReporter.create(config(), ZipkinReporter.Options.defaults().maxBatchSize(1))) {
      r.report(
          List.of(
              ZipkinSpan.builder(TRACE.toUpperCase(), "00000000000000AA").tag("b", false).build(),
              ZipkinSpan.builder(TRACE, "00000000000000BB").tag("d", 1.0).build()));
    }
    assertThat(server.requests()).hasSize(2);
    assertThat(server.requests().get(0).body())
        .isEqualTo(
            "[{\"traceId\":\""
                + TRACE
                + "\",\"id\":\"00000000000000aa\",\"tags\":{\"b\":\"false\"}}]");
    assertThat(server.requests().get(1).body()).contains("\"d\":\"1\"");
  }

  @Test
  void invalidSpanIsRejectedLocally() {
    try (var r = ZipkinReporter.create(config())) {
      List<ZipkinSpan> bad = List.of(ZipkinSpan.builder("zz", "1").build());
      assertThatThrownBy(() -> r.report(bad)).isInstanceOf(ValidationException.class);
      assertThatThrownBy(() -> r.reportAsync(bad).join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ValidationException.class);
    }
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void serverRejectionsKeepTheVendorText() {
    Fixtures.enqueue(server, "report.invalidId");
    server.enqueue(413, "too big", "Content-Type", "text/plain; charset=utf-8");
    try (var r = ZipkinReporter.create(config())) {
      List<ZipkinSpan> one = List.of(ZipkinSpan.builder(TRACE, "0000000000000001").build());
      assertThatThrownBy(() -> r.report(one))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("should be lower-hex encoded")
          .hasMessageContaining("(HTTP 400)")
          .satisfies(e -> assertThat(((ValidationException) e).httpStatus()).hasValue(400));
      assertThatThrownBy(() -> r.report(one))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("payload too large: too big");
    }
    assertThat(server.requests()).hasSize(2);
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).method()).isEqualTo("POST");
    assertThat(errors.get(0).willRetry()).isFalse();
  }

  @Test
  void transientFailuresAreRetried() {
    server.enqueue(503, "busy").enqueue(502, "bad gw").enqueue(202, "");
    server.fallback(ex -> MockServer.send(ex, 503, "down"));
    try (var r = ZipkinReporter.create(config())) {
      List<ZipkinSpan> one = List.of(ZipkinSpan.builder(TRACE, "0000000000000001").build());
      r.report(one);
      assertThat(server.requests()).hasSize(3);
      assertThatThrownBy(() -> r.report(one)).isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void optionsAndLifecycle() {
    assertThatThrownBy(() -> ZipkinReporter.Options.defaults().maxBatchSize(0))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> ZipkinReporter.Options.defaults().compression(null))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(
            () ->
                ZipkinReporter.create(
                    config(), ZipkinReporter.Options.defaults().endpoint("ftp://x")))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(
            () -> ZipkinReporter.create(config(), ZipkinReporter.Options.defaults().endpoint("::")))
        .isInstanceOf(ConfigException.class);
    try (var client = ZipkinClient.create(config())) {
      assertThatThrownBy(
              () -> client.createReporter(ZipkinReporter.Options.defaults().endpoint("relative")))
          .isInstanceOf(ConfigException.class);
      assertThat(client.transport().isClosed()).isFalse();
    }
    ZipkinReporter r = ZipkinReporter.create(config());
    r.close();
    r.close();
    assertThatThrownBy(
            () -> r.report(List.of(ZipkinSpan.builder(TRACE, "0000000000000001").build())))
        .isInstanceOf(CancelledException.class);
    try (var env = ZipkinReporter.fromEnv()) {
      assertThat(env.endpoint()).endsWith("/api/v2/spans");
    }
  }

  static String gunzip(byte[] data) throws IOException {
    try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
