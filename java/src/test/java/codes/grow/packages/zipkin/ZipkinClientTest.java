package codes.grow.packages.zipkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.zipkin.core.AuthenticationException;
import codes.grow.packages.zipkin.core.CancelledException;
import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.core.HealthStatus;
import codes.grow.packages.zipkin.core.Hooks;
import codes.grow.packages.zipkin.core.Logger;
import codes.grow.packages.zipkin.core.MockServer;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.core.UnsupportedException;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZipkinClientTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private static ZipkinConfig.Builder cfg() {
    return ZipkinConfig.builder();
  }

  private ZipkinClient client() {
    return ZipkinClient.builder()
        .url(server.url())
        .header("Authorization", "Bearer tok-123456")
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
        .build();
  }

  @Test
  void healthVariants() {
    Fixtures.enqueue(server, "health.ok");
    server
        .enqueue(503, "{\"status\":\"DOWN\",\"zipkin\":{\"status\":\"DOWN\"}}")
        .enqueue(503, "storage down", "Content-Type", "text/plain")
        .enqueue(500, "oops")
        .enqueue(200, "[1]")
        .enqueue(200, "{\"status\":\"DOWN\"}");
    try (var c = client()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isTrue();
      assertThat(h.status()).isEqualTo("UP");
      assertThat(h.details()).containsEntry("zipkin", "UP");
      assertThat(h.details().get("storage")).contains("InMemoryStorage");
      HealthStatus down = c.health();
      assertThat(down.healthy()).isFalse();
      assertThat(down.status()).isEqualTo("DOWN");
      assertThat(down.details()).containsEntry("httpStatus", "503");
      assertThat(c.health().status()).isEqualTo("DOWN");
      assertThat(c.health().status()).isEqualTo("unhealthy");
      assertThat(c.healthAsync().join()).isEqualTo(new HealthStatus(true, "UP", Map.of()));
      assertThat(c.health().healthy()).isFalse();
      assertThat(server.requests()).hasSize(6);
      assertThat(server.requests().get(0).uri()).isEqualTo("/health");
    }
  }

  @Test
  void core_health_unreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    String url = "http://127.0.0.1:" + port;
    try (var c = ZipkinClient.builder().url(url).build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isFalse();
      assertThat(h.status()).isEqualTo("unreachable");
      assertThat(c.healthAsync().join().status()).isEqualTo("unreachable");
    }
  }

  @Test
  void healthThrowsOnlyForCancel() {
    ZipkinClient c = client();
    c.close();
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.healthAsync().join()).hasCauseInstanceOf(CancelledException.class);
  }

  @Test
  void pingUsesTheServicesApiAndPassesGatewayHeaders() {
    Fixtures.enqueue(server, "listServices.ok");
    server.enqueue(401, "{\"message\":\"bad token tok-123456\"}");
    Fixtures.enqueue(server, "listServices.empty");
    try (var c = client()) {
      assertThat(c.ping()).isPositive();
      assertThat(server.requests().get(0).header("Authorization")).isEqualTo("Bearer tok-123456");
      assertThat(server.requests().get(0).uri()).isEqualTo("/api/v2/services");
      assertThat(server.requests().get(0).header("User-Agent")).startsWith("grow-codes-zipkin/");
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .message()
          .doesNotContain("tok-123456");
      assertThat(c.pingAsync().join()).isPositive();
    }
  }

  @Test
  void serverVersionAndInfo() {
    Fixtures.enqueue(server, "serverVersion.ok", "serverVersion.ok");
    server
        .enqueue(404, "not found", "Content-Type", "text/*")
        .enqueue(200, "{\"other\":1}")
        .enqueue(200, "not json", "Content-Type", "text/plain")
        .enqueue(200, "{\"zipkin\":{\"version\":\"\",\"commit\":7}}");
    Fixtures.enqueue(server, "serverVersion.ok");
    try (var c = client()) {
      assertThat(c.serverVersion()).isEqualTo("3.6.1");
      ZipkinServerInfo info = c.serverInfo();
      assertThat(info.version()).contains("3.6.1");
      assertThat(info.commit()).contains("b2b0c98");
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThat(c.serverInfo().version()).isEmpty();
      assertThat(c.serverInfo()).isEqualTo(new ZipkinServerInfo(null, null));
      assertThat(c.serverVersionAsync().join()).isEqualTo("3.6.1");
      assertThat(server.requests().get(0).uri()).isEqualTo("/info");
    }
  }

  @Test
  void queryAndReporterShareTheTransport() {
    Fixtures.enqueue(server, "listServices.ok", "report.json");
    server.enqueue(202, "");
    try (var c = client()) {
      assertThat(c.query()).isSameAs(c.query());
      assertThat(c.query().listServices()).isNotEmpty();
      assertThat(c.reporter()).isSameAs(c.reporter());
      assertThat(c.reporter().endpoint()).isEqualTo(server.url() + "/api/v2/spans");
      ZipkinSpan span =
          ZipkinSpan.builder("463ac35c9f6413ad", "463ac35c9f6413ad").name("op").build();
      c.reporter().report(List.of(span));
      ZipkinReporter gz =
          c.createReporter(ZipkinReporter.Options.defaults().compression(Compression.GZIP));
      gz.report(List.of(span));
      gz.close();
      assertThat(c.transport().isClosed()).isFalse();
      assertThat(server.requests().get(2).header("Content-Encoding")).isEqualTo("gzip");
    }
  }

  @Test
  void core_close_idempotent() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    ZipkinClient c = client();
    c.register(closed::incrementAndGet);
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    ZipkinTracingHandle handle =
        c.initTracing(
            TracingConfig.builder("svc").setGlobal(false).build(),
            ZipkinTracing.Options.builder().ignoreEnv());
    assertThat(handle.endpoint()).isEqualTo(server.url() + "/api/v2/spans");
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(handle.isShutdown()).isTrue();
    assertThat(closed).hasValue(1);
    assertThat(c.transport().isClosed()).isTrue();
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.initTracing(TracingConfig.builder("svc").build()))
        .isInstanceOf(ConfigException.class);
  }

  @Test
  void initTracingUsesProcessEnvironmentByDefault() {
    try (var c = client()) {
      ZipkinTracingHandle h = c.initTracing(TracingConfig.builder("svc").setGlobal(false).build());
      assertThat(h.config().protocol()).isEqualTo(TracingConfig.Protocol.NATIVE);
      h.shutdown();
    }
  }

  @Test
  void builderSetsEveryField() {
    Hooks hooks = new Hooks() {};
    Logger logger = new Logger() {};
    try (var c =
        ZipkinClient.builder()
            .url(server.url() + "/gw/")
            .endpoint("http://collector:9411/api/v2/spans")
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://proxy.invalid:3128")
            .header("X-A", "1")
            .headers(Map.of("X-Api-Key", "s3cr3t-value"))
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(logger)
            .build()) {
      ZipkinConfig cfg = c.config();
      assertThat(cfg.url()).isEqualTo(server.url() + "/gw/");
      assertThat(cfg.endpoint()).isEqualTo("http://collector:9411/api/v2/spans");
      assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
      assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(cfg.retry().maxRetries()).isZero();
      assertThat(cfg.tls().isCustom()).isFalse();
      assertThat(cfg.proxy()).contains("http://proxy.invalid:3128");
      assertThat(cfg.headers()).containsEntry("X-A", "1");
      assertThat(cfg.userAgentSuffix()).contains("app/1");
      assertThat(cfg.hooks()).isSameAs(hooks);
      assertThat(cfg.logger()).isSameAs(logger);
      assertThat(c.toString()).doesNotContain("s3cr3t-value");
      assertThat(c.transport().config().knownSecrets()).containsExactly("s3cr3t-value");
      ZipkinConfig copy = cfg.toBuilder().build();
      assertThat(copy.toString()).isEqualTo(cfg.toString());
    }
  }

  @Test
  void core_redaction_config() {
    ZipkinConfig cfg =
        cfg()
            .url("http://user:pw@h:1")
            .header("Authorization", "Bearer tok-very-secret")
            .header("X-Plain", "")
            .proxy("http://u:proxypass@p:3128")
            .build();
    assertThat(cfg.toString())
        .doesNotContain("tok-very-secret")
        .doesNotContain("proxypass")
        .doesNotContain(":pw@");
    assertThat(cfg.knownSecrets()).containsExactly("Bearer tok-very-secret");
    assertThat(cfg().build().toString()).contains("proxy=null");
  }

  @Test
  void endpointHelpers() {
    assertThat(ZipkinConfig.spansEndpoint("http://z:9411/"))
        .isEqualTo("http://z:9411/api/v2/spans");
    assertThat(ZipkinConfig.spansEndpoint("https://gw/zipkin"))
        .isEqualTo("https://gw/zipkin/api/v2/spans");
    assertThat(ZipkinConfig.spansEndpoint("http://z/api/v2/spans/"))
        .isEqualTo("http://z/api/v2/spans");
    assertThat(ZipkinConfig.baseFromEndpoint("http://z:9411/api/v2/spans"))
        .isEqualTo("http://z:9411");
    assertThat(ZipkinConfig.baseFromEndpoint("https://gw/zipkin/")).isEqualTo("https://gw/zipkin");
    assertThat(cfg().build().endpoint()).isEqualTo("http://127.0.0.1:9411/api/v2/spans");
    assertThat(cfg().url("https://gw/zipkin").endpoint("").build().endpoint())
        .isEqualTo("https://gw/zipkin/api/v2/spans");
  }

  @Test
  void core_config_env_precedence() {
    Map<String, String> env = new HashMap<>();
    env.put(P + "URL", "http://from-env:1");
    env.put("ZIPKIN_URL", "http://vendor:2");
    env.put("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://otel:3/api/v2/spans");
    env.put(P + "CONNECT_TIMEOUT", "1500");
    env.put(P + "REQUEST_TIMEOUT", "2s");
    env.put(P + "TOTAL_TIMEOUT", "1m");
    env.put(P + "MAX_RETRIES", "5");
    env.put(P + "CA_CERT", "/tmp/ca.pem");
    env.put(P + "CLIENT_CERT", "/tmp/c.pem");
    env.put(P + "CLIENT_KEY", "/tmp/k.pem");
    env.put(P + "INSECURE_SKIP_VERIFY", "on");
    env.put(P + "PROXY", "http://proxy:1");
    ZipkinConfig fromEnv = ZipkinConfig.fromEnv(env);
    assertThat(fromEnv.url()).isEqualTo("http://from-env:1");
    assertThat(fromEnv.endpoint()).isEqualTo("http://otel:3/api/v2/spans");
    assertThat(fromEnv.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(fromEnv.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(fromEnv.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(fromEnv.retry().maxRetries()).isEqualTo(5);
    assertThat(fromEnv.tls().insecureSkipVerify()).isTrue();
    assertThat(fromEnv.tls().caCertPath()).isPresent();
    assertThat(fromEnv.proxy()).contains("http://proxy:1");
    // vendor variables: ZIPKIN_URL, then the base of OTEL_EXPORTER_ZIPKIN_ENDPOINT
    assertThat(ZipkinConfig.fromEnv(Map.of("ZIPKIN_URL", "http://vendor:2")).endpoint())
        .isEqualTo("http://vendor:2/api/v2/spans");
    ZipkinConfig otel =
        ZipkinConfig.fromEnv(Map.of("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "https://gw/z/api/v2/spans"));
    assertThat(otel.url()).isEqualTo("https://gw/z");
    assertThat(otel.endpoint()).isEqualTo("https://gw/z/api/v2/spans");
    ZipkinConfig grow =
        ZipkinConfig.fromEnv(
            Map.of(P + "ENDPOINT", "http://e:1/x", "OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://o:2"));
    assertThat(grow.endpoint()).isEqualTo("http://e:1/x");
    assertThat(grow.url()).isEqualTo("http://o:2");
    // explicit values win over the environment
    ZipkinConfig explicit =
        cfg()
            .url("http://explicit:2")
            .endpoint("http://explicit:2/in")
            .connectTimeout(Duration.ofSeconds(9))
            .requestTimeout(Duration.ofSeconds(9))
            .totalTimeout(Duration.ofSeconds(9))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://p2:1")
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.url()).isEqualTo("http://explicit:2");
    assertThat(explicit.endpoint()).isEqualTo("http://explicit:2/in");
    assertThat(explicit.retry().maxRetries()).isZero();
    assertThat(explicit.tls().insecureSkipVerify()).isFalse();
    assertThat(explicit.proxy()).contains("http://p2:1");
    // defaults when nothing is set
    ZipkinConfig defaults = ZipkinConfig.fromEnv(Map.of());
    assertThat(defaults.url()).isEqualTo(ZipkinConfig.DEFAULT_URL);
    assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(defaults.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(defaults.retry().maxRetries()).isEqualTo(3);
    // invalid values name the variable, never the value
    Map<String, String> badTimeout = Map.of(P + "TOTAL_TIMEOUT", "forever");
    assertThatThrownBy(() -> ZipkinConfig.fromEnv(badTimeout))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "TOTAL_TIMEOUT")
        .message()
        .doesNotContain("forever");
    Map<String, String> badRetries = Map.of(P + "MAX_RETRIES", "-1");
    assertThatThrownBy(() -> ZipkinConfig.fromEnv(badRetries))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "MAX_RETRIES");
    assertThat(ZipkinConfig.fromEnv()).isNotNull();
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> cfg().url("ftp://x").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().url("http://[").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().url("mailto:x").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().endpoint("ftp://x/api").build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("endpoint");
    assertThatThrownBy(() -> cfg().connectTimeout(Duration.ZERO).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().requestTimeout(Duration.ofSeconds(-1)).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().totalTimeout(Duration.ZERO).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().tls(TlsOptions.builder().clientCertPem("x").build()).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().proxy("relative").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().proxy("http://[").build()).isInstanceOf(ConfigException.class);
  }

  @Test
  void createAndFromEnv() {
    try (var c = ZipkinClient.create(cfg().url(server.url()).build())) {
      assertThat(c.isClosed()).isFalse();
    }
    try (var c = ZipkinClient.fromEnv()) {
      assertThat(c.config()).isNotNull();
    }
    EnvReader env = EnvReader.of(Map.of(P + "URL", server.url()));
    try (var c = ZipkinClient.builder().fromEnv(env).build()) {
      assertThat(c.config().url()).isEqualTo(server.url());
    }
  }
}
