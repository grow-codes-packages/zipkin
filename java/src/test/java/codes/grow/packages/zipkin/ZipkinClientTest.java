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
import codes.grow.packages.zipkin.core.Secret;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.core.UnsupportedException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
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
        .token("tok-123456")
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
        .build();
  }

  @Test
  void healthVariants() {
    server
        .enqueue(200, "{\"status\":\"ok\",\"version\":\"1.0\",\"nested\":{}}")
        .enqueue(200, "{\"status\":\"degraded\"}")
        .enqueue(503, "{\"status\":\"sealed\"}")
        .enqueue(500, "oops")
        .enqueue(200, "[1]")
        .enqueue(200, "{\"status\":5}");
    try (var c = client()) {
      HealthStatus h = c.health();
      assertThat(h).isEqualTo(new HealthStatus(true, "ok", Map.of("version", "1.0")));
      assertThat(c.health().status()).isEqualTo("degraded");
      HealthStatus sealed = c.health();
      assertThat(sealed.healthy()).isFalse();
      assertThat(sealed.status()).isEqualTo("sealed");
      assertThat(sealed.details()).containsEntry("httpStatus", "503");
      assertThat(c.health().status()).isEqualTo("unhealthy");
      assertThat(c.health().status()).isEqualTo("ok");
      assertThat(c.healthAsync().join().details()).containsEntry("status", "5");
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
  void pingSendsBearerToken() {
    server
        .enqueue(200, "{}")
        .enqueue(401, "{\"message\":\"bad token tok-123456\"}")
        .enqueue(200, "{}");
    try (var c = client()) {
      assertThat(c.ping()).isPositive();
      assertThat(server.requests().get(0).header("Authorization")).isEqualTo("Bearer tok-123456");
      assertThat(server.requests().get(0).uri()).isEqualTo("/v1/ping");
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .message()
          .doesNotContain("tok-123456");
      assertThat(c.pingAsync().join()).isPositive();
    }
  }

  @Test
  void pingWithoutToken() {
    server.enqueue(200, "{}");
    try (var c = ZipkinClient.builder().url(server.url()).build()) {
      c.ping();
      assertThat(server.requests().get(0).header("Authorization")).isNull();
    }
  }

  @Test
  void serverVersion() {
    server
        .enqueue(200, "{\"version\":\"1.2.3\"}")
        .enqueue(404, "")
        .enqueue(200, "{\"other\":1}")
        .enqueue(200, "{\"version\":\"2.0.0\"}");
    try (var c = client()) {
      assertThat(c.serverVersion()).isEqualTo("1.2.3");
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThat(c.serverVersionAsync().join()).isEqualTo("2.0.0");
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
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(closed).hasValue(1);
    assertThat(c.transport().isClosed()).isTrue();
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
  }

  @Test
  void builderSetsEveryField() {
    Hooks hooks = new Hooks() {};
    Logger logger = new Logger() {};
    try (var c =
        ZipkinClient.builder()
            .url(server.url())
            .token(Secret.of("s3cr3t-value"))
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://proxy.invalid:3128")
            .header("X-A", "1")
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(logger)
            .build()) {
      ZipkinConfig cfg = c.config();
      assertThat(cfg.url()).isEqualTo(server.url());
      assertThat(cfg.token()).isPresent();
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
      cfg.token().get().clear();
      assertThat(c.transport().config().knownSecrets()).isEmpty();
    }
  }

  @Test
  void core_redaction_config() {
    ZipkinConfig cfg =
        cfg()
            .url("http://user:pw@h:1")
            .token("tok-very-secret")
            .header("Authorization", "Bearer tok-very-secret")
            .proxy("http://u:proxypass@p:3128")
            .build();
    assertThat(cfg.toString())
        .doesNotContain("tok-very-secret")
        .doesNotContain("proxypass")
        .doesNotContain(":pw@")
        .contains("token=[REDACTED]");
    assertThat(cfg().build().toString()).contains("token=null").contains("proxy=null");
  }

  @Test
  void core_config_env_precedence() {
    Map<String, String> env = new HashMap<>();
    env.put(P + "URL", "http://from-env:1");
    env.put(P + "TOKEN", "env-token");
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
    assertThat(fromEnv.token().map(Secret::reveal)).contains("env-token");
    assertThat(fromEnv.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(fromEnv.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(fromEnv.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(fromEnv.retry().maxRetries()).isEqualTo(5);
    assertThat(fromEnv.tls().insecureSkipVerify()).isTrue();
    assertThat(fromEnv.tls().caCertPath()).isPresent();
    assertThat(fromEnv.proxy()).contains("http://proxy:1");
    // explicit values win over the environment
    ZipkinConfig explicit =
        cfg()
            .url("http://explicit:2")
            .token("explicit-token")
            .connectTimeout(Duration.ofSeconds(9))
            .requestTimeout(Duration.ofSeconds(9))
            .totalTimeout(Duration.ofSeconds(9))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://p2:1")
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.url()).isEqualTo("http://explicit:2");
    assertThat(explicit.token().map(Secret::reveal)).contains("explicit-token");
    assertThat(explicit.retry().maxRetries()).isZero();
    assertThat(explicit.tls().insecureSkipVerify()).isFalse();
    assertThat(explicit.proxy()).contains("http://p2:1");
    // defaults when nothing is set
    ZipkinConfig defaults = ZipkinConfig.fromEnv(Map.of());
    assertThat(defaults.url()).isEqualTo(ZipkinConfig.DEFAULT_URL);
    assertThat(defaults.token()).isEmpty();
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
    assertThat(cfg().token((String) null).build().token()).isEmpty();
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
