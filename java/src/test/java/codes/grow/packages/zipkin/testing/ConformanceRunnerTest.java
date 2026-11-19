// Taken over from sdk-kit 0.1.0 (header removed): the core mock speaks the Zipkin API.
package codes.grow.packages.zipkin.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.core.Json;
import codes.grow.packages.zipkin.core.MockServer;
import codes.grow.packages.zipkin.core.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

class ConformanceRunnerTest {
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    server.fallback(
        ex -> {
          String path = ex.getRequestURI().getPath();
          switch (path) {
            case "/health":
              MockServer.send(ex, 200, "{\"status\":\"UP\"}");
              break;
            case "/api/v2/services":
              String auth = ex.getRequestHeaders().getFirst("Authorization");
              MockServer.send(ex, "Bearer t0ken".equals(auth) ? 200 : 401, "[]");
              break;
            case "/info":
              MockServer.send(ex, 200, "{\"zipkin\":{\"version\":\"3.6.1\"}}");
              break;
            default:
              MockServer.send(ex, 404, "{}");
          }
        });
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private ZipkinClient client() {
    return ZipkinClient.builder().url(server.url()).header("Authorization", "Bearer t0ken").build();
  }

  @Test
  void coreScenarioAgainstMockServer() {
    ConformanceReport report =
        ConformanceRunner.builder()
            .target("core", s -> client())
            .capabilities("version")
            .build()
            .run();
    report.assertPassed();
    assertThat(report.with(ConformanceReport.Status.PASSED))
        .extracting(ConformanceReport.ScenarioResult::id)
        .containsExactly("core.health");
    assertThat(report.summary()).contains("1 passed, 0 failed, 0 skipped");
    // without the "version" capability the serverVersion step is skipped
    ConformanceReport.ScenarioResult r =
        ConformanceRunner.builder().target("core", s -> client()).build().run().scenarios().get(0);
    assertThat(r.status()).isEqualTo(ConformanceReport.Status.PASSED);
    assertThat(r.steps())
        .extracting(ConformanceReport.StepResult::status)
        .contains(ConformanceReport.Status.SKIPPED);
  }

  @TestFactory
  Stream<DynamicTest> junitAdapterRunsCoreAndSkips() {
    return JUnitConformance.dynamicTests(
        ConformanceRunner.builder().target("core", s -> client()).capabilities("version").build());
  }

  @Test
  void junitAdapterReportsFailuresAndSkips() throws Throwable {
    List<DynamicTest> failing =
        JUnitConformance.dynamicTests(
                ConformanceRunner.builder()
                    .target(
                        "core",
                        s -> {
                          throw new IllegalStateException("no");
                        })
                    .build())
            .toList();
    assertThatThrownBy(() -> failing.get(0).getExecutable().execute())
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void scenarioDirFromFiles(@TempDir Path dir) throws Exception {
    write(
        dir,
        "a.json",
        "{\"id\":\"x.skip\",\"category\":\"x\",\"requires\":[\"query\"],\"steps\":[]}");
    write(
        dir,
        "b.json",
        "{\"id\":\"x.pass\",\"category\":\"x\",\"steps\":["
            + "{\"op\":\"echo\",\"args\":{\"v\":\"${run}\"},\"save\":\"first\"},"
            + "{\"op\":\"echo\",\"args\":{\"v\":\"${first}\"},\"expect\":{\"type\":\"string\"}},"
            + "{\"op\":\"obj\",\"save\":\"o\"},"
            + "{\"op\":\"echo\",\"args\":{\"v\":\"${o}|${prefix}|${serviceName}|${unknown}\"},"
            + "\"expect\":{\"contains\":\"{\\\"k\\\":1}|p-\"}},"
            + "{\"op\":\"nothing\",\"save\":\"empty\"},"
            + "{\"op\":\"echo\",\"args\":{\"v\":\"[${empty}]\"},\"expect\":{\"equals\":\"[]\"}},"
            + "{\"op\":\"opt\",\"save\":\"opt\"},"
            + "{\"op\":\"echo\",\"args\":{\"v\":\"${opt}\"},\"expect\":{\"equals\":\"inner\"}}"
            + "]}");
    write(
        dir,
        "c.json",
        "{\"id\":\"x.fail\",\"category\":\"x\",\"steps\":[{\"op\":\"echo\",\"args\":{\"v\":1},\"expect\":{\"equals\":2}},{\"op\":\"never\"}]}");
    write(
        dir,
        "d.json",
        "{\"id\":\"x.unknown\",\"category\":\"x\",\"steps\":[{\"op\":\"missing\"}]}");
    write(dir, "e.json", "{\"id\":\"y.noops\",\"category\":\"y\",\"steps\":[]}");
    write(dir, "f.json", "{\"id\":\"z.factory\",\"category\":\"z\",\"steps\":[]}");
    write(
        dir,
        "g.json",
        "{\"id\":\"x.throws\",\"category\":\"x\",\"steps\":[{\"op\":\"boom\",\"expect\":{\"error\":\"NOT_FOUND\"}},{\"op\":\"boom\"}]}");
    write(dir, "ignored.txt", "not a scenario");
    ConformanceRunner runner =
        ConformanceRunner.builder()
            .scenarioDir(dir)
            .target("x", s -> "target-" + s.id())
            .target("y", s -> "y")
            .target(
                "z",
                s -> {
                  throw new IllegalStateException("factory");
                })
            .prefix("p-${run}/")
            .serviceName("svc")
            .variable("extra", "1")
            .op("x", "echo", ctx -> ctx.substitute(ctx.args().get("v").asText()))
            .op("x", "obj", ctx -> Map.of("k", 1))
            .op("x", "nothing", ctx -> null)
            .op("x", "opt", ctx -> java.util.Optional.of("inner"))
            .op("z", "noop", ctx -> null)
            .op(
                "x",
                "boom",
                ctx -> {
                  throw new NotFoundException("nf");
                })
            .build();
    ConformanceReport report = runner.run();
    Map<String, ConformanceReport.ScenarioResult> byId = new java.util.HashMap<>();
    report.scenarios().forEach(s -> byId.put(s.id(), s));
    assertThat(byId.get("x.skip").status()).isEqualTo(ConformanceReport.Status.SKIPPED);
    assertThat(byId.get("x.skip").reason()).contains("query");
    assertThat(byId.get("x.pass").status())
        .as(byId.get("x.pass").describe())
        .isEqualTo(ConformanceReport.Status.PASSED);
    assertThat(byId.get("x.fail").status()).isEqualTo(ConformanceReport.Status.FAILED);
    assertThat(byId.get("x.fail").steps()).hasSize(1);
    assertThat(byId.get("x.unknown").reason()).contains("unknown op missing");
    assertThat(byId.get("y.noops").reason()).contains("no operations registered");
    assertThat(byId.get("z.factory").reason()).contains("target factory failed");
    assertThat(byId.get("x.throws").reason()).contains("unexpected error");
    assertThat(report.passed()).isFalse();
    assertThatThrownBy(report::assertPassed)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("x.fail");
    assertThat(
            ConformanceRunner.builder()
                .scenarioDir(dir)
                .target("x", s -> "t")
                .only("x.pass")
                .build()
                .scenarios())
        .extracting(Scenario::id)
        .containsExactly("x.pass");
    // a category without target is skipped when run directly
    Scenario other =
        Scenario.parse(
            Json.tree(
                "{\"id\":\"q\",\"category\":\"q\",\"steps\":[]}".getBytes(StandardCharsets.UTF_8)));
    assertThat(runner.runScenario(other).status()).isEqualTo(ConformanceReport.Status.SKIPPED);
    assertThatThrownBy(
            () ->
                ConformanceRunner.builder()
                    .scenarioDir(dir.resolve("nope"))
                    .target("x", s -> "t")
                    .build()
                    .scenarios())
        .isInstanceOf(UncheckedIOException.class);
  }

  private static void write(Path dir, String name, String json) throws Exception {
    Files.writeString(dir.resolve(name), json);
  }

  @Test
  void substitutesKeysAndValues() {
    JsonNode n =
        Json.tree(
            "{\"tree\":{\"root-${run}\":[\"child-${run}\"]},\"n\":1,\"l\":[\"${run}\",true]}"
                .getBytes(StandardCharsets.UTF_8));
    JsonNode out = ConformanceRunner.substituteNode(n, Map.of("run", "abc"));
    assertThat(out.get("tree").has("root-abc")).isTrue();
    assertThat(out.get("tree").get("root-abc").get(0).asText()).isEqualTo("child-abc");
    assertThat(out.get("l").get(0).asText()).isEqualTo("abc");
    assertThat(out.get("n").asInt()).isEqualTo(1);
    assertThat(ConformanceRunner.substitute("#{i} ${x} $${run}", Map.of("run", "r")))
        .isEqualTo("#{i} ${x} $r");
    assertThat(ConformanceRunner.randomRun()).matches("[a-z0-9]{8}");
  }

  @Test
  void parseErrorsAndAccessors() {
    assertThatThrownBy(
            () -> Scenario.parse(Json.tree("{\"id\":\"a\"}".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Scenario.parse(
                    Json.tree(
                        "{\"id\":\"a\",\"category\":\"c\",\"steps\":{}}"
                            .getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Scenario.parse(
                    Json.tree(
                        "{\"category\":\"c\",\"steps\":[]}".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Scenario.parse(
                    Json.tree("{\"id\":\"a\",\"steps\":[]}".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Scenario.parse(
                    Json.tree(
                        "{\"id\":\"a\",\"category\":\"c\",\"steps\":[{}]}"
                            .getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    Scenario s =
        Scenario.parse(
            Json.tree(
                "{\"id\":\"a\",\"category\":\"logging\",\"batchOptions\":{\"maxBatchSize\":5},\"steps\":[{\"op\":\"x\",\"requires\":[\"w\"]}]}"
                    .getBytes(StandardCharsets.UTF_8)));
    assertThat(s.batchOptions())
        .hasValueSatisfying(b -> assertThat(b.get("maxBatchSize").asInt()).isEqualTo(5));
    assertThat(s.description()).isEmpty();
    assertThat(s.steps().get(0).requires()).containsExactly("w");
    assertThat(s.steps().get(0).args().isObject()).isTrue();
    Scenario none =
        Scenario.parse(
            Json.tree(
                "{\"id\":\"a\",\"category\":\"c\",\"batchOptions\":null,\"steps\":[]}"
                    .getBytes(StandardCharsets.UTF_8)));
    assertThat(none.batchOptions()).isEmpty();
    StepContext ctx =
        new StepContext(
            s,
            s.steps().get(0),
            Json.tree("{\"a\":\"1\",\"n\":null}".getBytes(StandardCharsets.UTF_8)),
            "t",
            Map.of("run", "r"));
    assertThat(ctx.scenario()).isSameAs(s);
    assertThat(ctx.step().op()).isEqualTo("x");
    assertThat(ctx.arg("a")).isEqualTo("1");
    assertThatThrownBy(() -> ctx.arg("n")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ctx.arg("zz")).isInstanceOf(IllegalArgumentException.class);
    assertThat(ctx.optArg("a")).isPresent();
    assertThat(ctx.optArg("n")).isEmpty();
    assertThat(ctx.target()).isEqualTo("t");
    assertThat(ctx.variable("run")).contains("r");
    assertThat(ctx.substitute("${run}")).isEqualTo("r");
  }

  @Test
  void loadOpsAndScenarioDirLookup(@TempDir Path dir) throws Exception {
    assertThat(ConformanceRunner.loadOps(List.of("does.not.Exist"))).isEmpty();
    assertThat(ConformanceRunner.loadOps(List.of(CoreOps.class.getName()))).hasSize(1);
    assertThatThrownBy(() -> ConformanceRunner.loadOps(List.of(OpResult.class.getName())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ConformanceRunner.findScenarioDir()).isDirectory();
    System.setProperty("grow.conformance.dir", dir.toString());
    try {
      assertThat(ConformanceRunner.findScenarioDir()).isEqualTo(dir);
      System.setProperty("grow.conformance.dir", "");
      assertThat(ConformanceRunner.findScenarioDir()).isDirectory();
    } finally {
      System.clearProperty("grow.conformance.dir");
    }
    String old = System.getProperty("user.dir");
    assertThat(old).isNotNull();
  }

  @Test
  void opResultViews() {
    OpResult r = OpResult.of("v").withCount(3).withTree(Map.of("a", List.of("b")));
    assertThat(r.value()).isEqualTo("v");
    assertThat(r.count()).hasValue(3);
    assertThat(r.tree()).hasValueSatisfying(t -> assertThat(t).containsKey("a"));
    assertThat(OpResult.of(null).count()).isEmpty();
    assertThat(OpResult.of(null).tree()).isEmpty();
  }
}
