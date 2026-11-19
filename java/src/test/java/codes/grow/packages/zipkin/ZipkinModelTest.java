package codes.grow.packages.zipkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.query.Span;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ZipkinModelTest {
  private static final String TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c";

  @Test
  void traceIds() {
    assertThat(ZipkinModel.zipkinTraceId(" 5AF7183FB1D4CF5F ", "op")).isEqualTo("5af7183fb1d4cf5f");
    assertThat(ZipkinModel.zipkinTraceId(TRACE, "op")).isEqualTo(TRACE);
    for (String bad : new String[] {"zz", "", "0000000000000000", "123", TRACE + "0"}) {
      assertThatThrownBy(() -> ZipkinModel.zipkinTraceId(bad, "op"))
          .isInstanceOf(ValidationException.class)
          .hasMessageStartingWith("op: trace id");
    }
    assertThatThrownBy(() -> ZipkinModel.zipkinTraceId(null, "op"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void tagValues() {
    assertThat(ZipkinModel.tagValue("s")).isEqualTo("s");
    assertThat(ZipkinModel.tagValue('c')).isEqualTo("c");
    assertThat(ZipkinModel.tagValue(true)).isEqualTo("true");
    assertThat(ZipkinModel.tagValue(7)).isEqualTo("7");
    assertThat(ZipkinModel.tagValue(7L)).isEqualTo("7");
    assertThat(ZipkinModel.tagValue((short) 3)).isEqualTo("3");
    assertThat(ZipkinModel.tagValue((byte) 2)).isEqualTo("2");
    assertThat(ZipkinModel.tagValue(BigInteger.TEN)).isEqualTo("10");
    assertThat(ZipkinModel.tagValue(1.5)).isEqualTo("1.5");
    assertThat(ZipkinModel.tagValue(2.0)).isEqualTo("2");
    assertThat(ZipkinModel.tagValue(0.1f)).isEqualTo("0.1");
    assertThat(ZipkinModel.tagValue(1e-7)).isEqualTo("0.0000001");
    assertThat(ZipkinModel.tagValue(1e21)).isEqualTo("1000000000000000000000");
    assertThat(ZipkinModel.tagValue(new BigDecimal("1.50"))).isEqualTo("1.5");
    assertThat(ZipkinModel.tagValue(Double.NaN)).isEqualTo("NaN");
    assertThat(ZipkinModel.tagValue(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
    assertThat(ZipkinModel.tagValue(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    assertThat(ZipkinModel.tagValue(null)).isEqualTo("");
    assertThat(ZipkinModel.tagValue(List.of("a", 1L, 2.0, 2.5, true)))
        .isEqualTo("[\"a\",1,2,2.5,true]");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("k", null);
    m.put("n", Double.NaN);
    m.put("big", 1e22);
    m.put("bi", BigInteger.TWO);
    m.put("c", 'x');
    m.put(
        "o",
        new Object() {
          @Override
          public String toString() {
            return "obj";
          }
        });
    assertThat(ZipkinModel.tagValue(m))
        .isEqualTo("{\"k\":null,\"n\":null,\"big\":1.0E22,\"bi\":2,\"c\":\"x\",\"o\":\"obj\"}");
    assertThat(ZipkinModel.tagValue(new int[] {1, 2})).isEqualTo("[1,2]");
    assertThat(ZipkinModel.tagValue(Duration.ofSeconds(1))).isEqualTo("PT1S");
    assertThat(ZipkinModel.jsonValue(Json.mapper().createObjectNode()).isObject()).isTrue();
  }

  @Test
  void builderAndToBuilder() {
    Instant t = Instant.ofEpochSecond(1_795_035_874L, 5_000_999);
    ZipkinSpan s =
        ZipkinSpan.builder(TRACE, "0000000000000002")
            .parentId("0000000000000001")
            .kind("client")
            .name("get")
            .timestamp(t)
            .duration(Duration.ofMillis(100))
            .localServiceName("a")
            .remoteServiceName("b")
            .annotation(1L, "x")
            .tag("n", 1)
            .tag("gone", "v")
            .tag("gone", null)
            .tags(Map.of("b", true))
            .debug(true)
            .shared(true)
            .build();
    assertThat(s.timestamp()).hasValue(1_795_035_874_005_000L);
    assertThat(s.duration()).hasValue(100_000L);
    assertThat(s.kind()).contains(ZipkinKind.CLIENT);
    assertThat(s.tags()).containsExactly(Map.entry("n", "1"), Map.entry("b", "true"));
    assertThat(s.toBuilder().build()).isEqualTo(s);
    ZipkinSpan bare = ZipkinSpan.builder(TRACE, "0000000000000002").kind((String) null).build();
    assertThat(bare.toBuilder().build()).isEqualTo(bare);
    assertThat(
            new ZipkinSpan(
                    TRACE, null, "1", null, null, null, null, null, null, null, null, false, false)
                .tags())
        .isEmpty();
    assertThatThrownBy(() -> ZipkinKind.parse("internal")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ZipkinKind.parse(null)).isInstanceOf(ValidationException.class);
    assertThat(ZipkinKind.parse(" Producer ")).isEqualTo(ZipkinKind.PRODUCER);
    assertThat(new ZipkinEndpoint(null, null, null, null).isEmpty()).isTrue();
    assertThat(ZipkinEndpoint.of("", "1.2.3.4", null, 0).port()).isEmpty();
  }

  @Test
  void normalizeLowerCasesAndDropsEmptyFields() {
    ZipkinSpan in =
        ZipkinSpan.builder(TRACE.toUpperCase(), "00000000000000AB")
            .parentId("00000000000000CD")
            .kind(ZipkinKind.SERVER)
            .name("")
            .timestamp(0L)
            .duration(0L)
            .localEndpoint(ZipkinEndpoint.of(null, null, null, null))
            .remoteEndpoint(
                new ZipkinEndpoint(
                    Optional.of("b"), Optional.empty(), Optional.of("::1"), OptionalInt.of(0)))
            .annotation(5L, "a")
            .tag("k", "v")
            .build();
    ZipkinSpan out = ZipkinModel.normalize(in, "op");
    assertThat(out.traceId()).isEqualTo(TRACE);
    assertThat(out.id()).isEqualTo("00000000000000ab");
    assertThat(out.parentId()).contains("00000000000000cd");
    assertThat(out.name()).isEmpty();
    assertThat(out.timestamp()).isEmpty();
    assertThat(out.duration()).isEmpty();
    assertThat(out.localEndpoint()).isEmpty();
    assertThat(out.remoteEndpoint().get().port()).isEmpty();
    assertThat(out.remoteEndpoint().get().ipv6()).contains("::1");
    assertThat(ZipkinModel.toJson(out).toString())
        .isEqualTo(
            "{\"traceId\":\""
                + TRACE
                + "\",\"parentId\":\"00000000000000cd\","
                + "\"id\":\"00000000000000ab\",\"kind\":\"SERVER\","
                + "\"remoteEndpoint\":{\"serviceName\":\"b\",\"ipv6\":\"::1\"},"
                + "\"annotations\":[{\"timestamp\":5,\"value\":\"a\"}],\"tags\":{\"k\":\"v\"}}");
  }

  @Test
  void normalizeRejectsInvalidSpans() {
    String id = "0000000000000001";
    assertThatThrownBy(() -> ZipkinModel.normalize(null, "op"))
        .isInstanceOf(ValidationException.class);
    List<ZipkinSpan> bad =
        List.of(
            ZipkinSpan.builder("zz", id).build(),
            ZipkinSpan.builder(TRACE, "1").build(),
            ZipkinSpan.builder(TRACE, id).parentId("xyz").build(),
            ZipkinSpan.builder(TRACE, id).timestamp(-1L).build(),
            ZipkinSpan.builder(TRACE, id).duration(-1L).build(),
            ZipkinSpan.builder(TRACE, id).annotation(1L, "").build(),
            ZipkinSpan.builder(TRACE, id).annotation(0L, "x").build(),
            ZipkinSpan.builder(TRACE, id).annotation(-1L, "x").build(),
            ZipkinSpan.builder(TRACE, id)
                .localEndpoint(ZipkinEndpoint.of("a", null, null, 70000))
                .build(),
            ZipkinSpan.builder(TRACE, id)
                .localEndpoint(ZipkinEndpoint.of("a", null, null, -1))
                .build());
    for (ZipkinSpan s : bad) {
      assertThatThrownBy(() -> ZipkinModel.normalize(s, "zipkin.report"))
          .isInstanceOf(ValidationException.class)
          .hasMessageStartingWith("zipkin.report: ");
    }
  }

  @Test
  void decodeRoundTripsTheRecordedTrace() {
    JsonNode body = Fixtures.load("getTrace.ok").path("response").path("body");
    List<ZipkinSpan> spans = ZipkinModel.spansFromBody(body);
    assertThat(spans).hasSize(3);
    assertThat(
            Json.tree(ZipkinModel.toJson(spans).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isEqualTo(body);
    ZipkinSpan first = spans.get(0);
    assertThat(first.localEndpoint().get().ipv4()).contains("10.0.0.1");
    assertThat(first.localEndpoint().get().port()).hasValue(8080);
    assertThat(first.annotations())
        .containsExactly(new ZipkinAnnotation(1795035874001000L, "cache.miss"));
    JsonNode odd =
        Json.tree(
            ("[1,{\"traceId\":1,\"id\":\"a\"},{\"traceId\":\"a\"},{\"traceId\":\""
                    + TRACE
                    + "\",\"id\":\"0000000000000001\",\"kind\":\"WEIRD\",\"timestamp\":\"x\","
                    + "\"localEndpoint\":{},\"remoteEndpoint\":5,\"annotations\":[{\"value\":1}],"
                    + "\"tags\":{\"n\":7,\"o\":{\"a\":1}},\"debug\":true,\"shared\":true,\"parentId\":\"\"}]")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<ZipkinSpan> decoded = ZipkinModel.spansFromBody(odd);
    assertThat(decoded).hasSize(1);
    ZipkinSpan d = decoded.get(0);
    assertThat(d.kind()).isEmpty();
    assertThat(d.timestamp()).isEmpty();
    assertThat(d.localEndpoint()).isEmpty();
    assertThat(d.remoteEndpoint()).isEmpty();
    assertThat(d.annotations()).isEmpty();
    assertThat(d.parentId()).isEmpty();
    assertThat(d.tags()).containsEntry("n", "7").containsEntry("o", "{\"a\":1}");
    assertThat(d.debug()).isTrue();
    assertThat(d.shared()).isTrue();
    assertThat(ZipkinModel.toJson(d).path("debug").asBoolean()).isTrue();
    assertThat(ZipkinModel.spansFromBody(null)).isEmpty();
    assertThat(ZipkinModel.spansFromBody(Json.mapper().createObjectNode())).isEmpty();
    assertThat(ZipkinModel.tracesFromBody(Json.mapper().createObjectNode())).isEmpty();
    assertThat(ZipkinModel.tracesFromBody(Json.tree("[[],[1]]".getBytes()))).isEmpty();
    assertThat(ZipkinModel.stringsFromBody(Json.tree("[\"a\",1]".getBytes()))).containsExactly("a");
    assertThat(ZipkinModel.stringsFromBody(null)).isEmpty();
  }

  @Test
  void dependencies() {
    JsonNode body = Fixtures.load("getDependencies.ok").path("response").path("body");
    assertThat(ZipkinModel.dependenciesFromBody(body))
        .containsExactly(
            new ZipkinDependencyLink("grow-frontend", "grow-backend", 1, 1),
            new ZipkinDependencyLink("grow-mixed", "kafka-broker", 1, 0));
    assertThat(
            ZipkinModel.dependenciesFromBody(
                Json.tree(
                    "[{\"parent\":\"a\",\"child\":\"b\"},{\"parent\":1},2,{\"parent\":\"a\",\"child\":\"a\"}]"
                        .getBytes())))
        .containsExactly(
            new ZipkinDependencyLink("a", "a", 0, 0), new ZipkinDependencyLink("a", "b", 0, 0));
    assertThat(ZipkinModel.dependenciesFromBody(null)).isEmpty();
    assertThat(new ZipkinDependencyLink("a", "b", 3, 1).toDependencyLink().callCount())
        .isEqualTo(3);
  }

  @Test
  void toSpanMapsStatusKindAndEvents() {
    List<ZipkinSpan> spans =
        ZipkinModel.spansFromBody(Fixtures.load("getTrace.ok").path("response").path("body"));
    Trace trace = ZipkinModel.toTrace(spans).orElseThrow();
    assertThat(trace.traceId()).isEqualTo(TRACE);
    Span root = trace.root().orElseThrow();
    assertThat(root.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(root.serviceName()).isEqualTo("grow-frontend");
    assertThat(root.events().get(0).name()).isEqualTo("cache.miss");
    assertThat(root.endMicros() - root.startMicros()).isEqualTo(150_000);
    Span backend = trace.spans().get(2);
    assertThat(backend.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(backend.status().message()).contains("boom");
    TraceSummary summary = ZipkinModel.summarize(spans).orElseThrow();
    assertThat(summary.rootName()).contains("get /api/orders");
    assertThat(summary.spanCount()).isEqualTo(3);
    assertThat(summary.durationMicros()).isEqualTo(150_000);
    assertThat(ZipkinModel.toTrace(List.of())).isEmpty();
    assertThat(ZipkinModel.summarize(List.of())).isEmpty();

    String id = "0000000000000001";
    Span internal = ZipkinModel.toSpan(ZipkinSpan.builder("463ac35c9f6413ad", id).build());
    assertThat(internal.kind()).isEqualTo(Span.Kind.INTERNAL);
    assertThat(internal.traceId()).isEqualTo("0000000000000000463ac35c9f6413ad");
    assertThat(internal.name()).isEmpty();
    assertThat(internal.serviceName()).isEmpty();
    assertThat(internal.status()).isEqualTo(Span.Status.UNSET);
    assertThat(ZipkinModel.toSpan(ZipkinSpan.builder(TRACE, id).tag("error", "").build()).status())
        .isEqualTo(new Span.Status(Span.StatusCode.ERROR, Optional.empty()));
    assertThat(
            ZipkinModel.toSpan(
                    ZipkinSpan.builder(TRACE, id).tag("otel.status_code", "error").build())
                .status()
                .code())
        .isEqualTo(Span.StatusCode.ERROR);
    assertThat(
            ZipkinModel.toSpan(ZipkinSpan.builder(TRACE, id).tag("otel.status_code", "OK").build())
                .status()
                .code())
        .isEqualTo(Span.StatusCode.OK);
    assertThat(
            ZipkinModel.toSpan(
                    ZipkinSpan.builder(TRACE, id)
                        .localEndpoint(ZipkinEndpoint.of(null, "1.1.1.1", null, null))
                        .build())
                .serviceName())
        .isEmpty();
  }

  @Test
  void annotationsDecodeEventAttributes() {
    Span.Event e =
        ZipkinModel.annotationToEvent(new ZipkinAnnotation(5, "{\"retry\":{\"n\":2,\"ok\":true}}"));
    assertThat(e.name()).isEqualTo("retry");
    assertThat(e.timeMicros()).isEqualTo(5);
    assertThat(e.attributes()).containsEntry("n", 2).containsEntry("ok", true);
    for (String v : new String[] {"{bad}", "{\"a\":1}", "{\"a\":{},\"b\":{}}", "plain", "{", "}"}) {
      Span.Event plain = ZipkinModel.annotationToEvent(new ZipkinAnnotation(1, v));
      assertThat(plain.name()).isEqualTo(v);
      assertThat(plain.attributes()).isEmpty();
    }
    assertThat(
            new ZipkinSpan(
                    TRACE,
                    Optional.of(""),
                    "1",
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Map.of(),
                    false,
                    false)
                .parentId())
        .isEmpty();
  }
}
