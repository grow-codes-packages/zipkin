package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.AuthenticationException;
import codes.grow.packages.zipkin.core.CancelledException;
import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.core.HealthCheck;
import codes.grow.packages.zipkin.core.HealthStatus;
import codes.grow.packages.zipkin.core.Hooks;
import codes.grow.packages.zipkin.core.HttpTransport;
import codes.grow.packages.zipkin.core.Logger;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.core.TransportRequest;
import codes.grow.packages.zipkin.core.TransportResponse;
import codes.grow.packages.zipkin.core.UnsupportedException;
import codes.grow.packages.zipkin.core.ZipkinException;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for Zipkin (port 9411): health, ping and server version, plus the query API ({@link
 * #query()}), the direct span reporter ({@link #reporter()}) and tracing setup ({@link
 * #initTracing}).
 *
 * <p>Thread-safe; create one per server and close it when done. {@link #close()} also shuts down
 * every tracing handle created through this client.
 *
 * <pre>{@code
 * try (ZipkinClient client = ZipkinClient.fromEnv()) {
 *   HealthStatus health = client.health();
 *   List<String> services = client.query().listServices();
 * }
 * }</pre>
 *
 * <p>Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class ZipkinClient implements AutoCloseable {
  private final ZipkinConfig config;
  private final HttpTransport transport;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();
  private final ZipkinQueryClient query;
  private final ZipkinReporter reporter;

  private ZipkinClient(ZipkinConfig config) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
    this.query = new ZipkinQueryClient(transport, false, Clock.systemUTC());
    this.reporter = new ZipkinReporter(transport, false, config, ZipkinReporter.Options.defaults());
  }

  /**
   * Creates a client.
   *
   * @param config validated configuration
   * @return a new client
   * @throws ConfigException when TLS or proxy settings are invalid
   */
  public static ZipkinClient create(ZipkinConfig config) {
    config.validate();
    return new ZipkinClient(config);
  }

  /**
   * Creates a client from {@code GROW_ZIPKIN_*}, {@code ZIPKIN_URL} and {@code
   * OTEL_EXPORTER_ZIPKIN_ENDPOINT}.
   *
   * @return a new client
   */
  public static ZipkinClient fromEnv() {
    return create(ZipkinConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public ZipkinConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport, for service modules built on this client.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * The trace query client (shares this client's connections).
   *
   * @return the query client
   */
  public ZipkinQueryClient query() {
    return query;
  }

  /**
   * The span reporter (shares this client's connections; no compression, 512 spans per POST, the
   * config's endpoint).
   *
   * @return the reporter
   */
  public ZipkinReporter reporter() {
    return reporter;
  }

  /**
   * A reporter with other options that shares this client's connections.
   *
   * @param options compression, batch size and endpoint
   * @return the reporter
   */
  public ZipkinReporter createReporter(ZipkinReporter.Options options) {
    return new ZipkinReporter(transport, false, config, options);
  }

  /**
   * Registers a resource (watcher, renewer, batch processor) closed with the client.
   *
   * @param resource the resource
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      resources.add(resource);
    }
  }

  /**
   * Liveness: {@code GET /health} → {@code {"status":"UP"}} with the {@code zipkin} and {@code
   * storage} details. A 503 ({@code DOWN}, storage unavailable) gives {@code healthy=false} without
   * retries. Never throws for an unhealthy or unreachable service.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    return transport
        .sendAsync(
            TransportRequest.get("/health")
                .operation("health")
                .acceptAnyStatus()
                .maxRetries(0)
                .build())
        .handle(
            (resp, err) -> {
              if (err != null) {
                ZipkinException e = HttpTransport.asSdkException(err, "health");
                if (e instanceof CancelledException || e instanceof ConfigException) {
                  throw e;
                }
                return HealthCheck.fromError(e);
              }
              return health(resp);
            });
  }

  private static HealthStatus health(TransportResponse resp) {
    JsonNode body = null;
    try {
      body = resp.jsonNode();
    } catch (ZipkinException ignored) {
      // not JSON
    }
    Map<String, String> details = new LinkedHashMap<>();
    boolean ok = resp.status() >= 200 && resp.status() < 300;
    String status = ok ? "UP" : resp.status() == 503 ? "DOWN" : "unhealthy";
    if (body != null && body.isObject()) {
      if (body.path("status").isTextual()) {
        status = body.get("status").asText();
      }
      JsonNode zipkin = body.path("zipkin");
      if (zipkin.path("status").isTextual()) {
        details.put("zipkin", zipkin.get("status").asText());
      }
      if (zipkin.has("details")) {
        details.put("storage", zipkin.get("details").toString());
      }
    }
    if (!ok) {
      details.put("httpStatus", Integer.toString(resp.status()));
    }
    return new HealthStatus(ok && !"DOWN".equalsIgnoreCase(status), status, details);
  }

  /**
   * Round trip through the query API and storage: {@code GET /api/v2/services} (through a gateway
   * this also proves the auth headers).
   *
   * @return the latency
   * @throws AuthenticationException when a gateway rejects the credentials
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    return ZipkinHttp.send(
            transport, TransportRequest.get("/api/v2/services").operation("ping"), false)
        .thenApply(r -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * Build information: {@code GET /info} → {@code {"zipkin":{"version","commit"}}}.
   *
   * @return the info (empty fields when the server or a gateway reports none)
   */
  public ZipkinServerInfo serverInfo() {
    return HttpTransport.await(serverInfoAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverInfo()}.
   *
   * @return a future with the info
   */
  public CompletableFuture<ZipkinServerInfo> serverInfoAsync() {
    return ZipkinHttp.send(
            transport, TransportRequest.get("/info").operation("serverVersion"), true)
        .thenApply(ZipkinClient::info);
  }

  private static ZipkinServerInfo info(TransportResponse r) {
    if (r.status() == 404) {
      return new ZipkinServerInfo(Optional.empty(), Optional.empty());
    }
    JsonNode zipkin;
    try {
      zipkin = r.jsonNode().path("zipkin");
    } catch (ZipkinException e) {
      return new ZipkinServerInfo(Optional.empty(), Optional.empty());
    }
    return new ZipkinServerInfo(text(zipkin, "version"), text(zipkin, "commit"));
  }

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v != null && v.isTextual() && !v.asText().isEmpty()
        ? Optional.of(v.asText())
        : Optional.empty();
  }

  /**
   * Server version (for example {@code 3.6.1}) from {@code GET /info}.
   *
   * @return the version string
   * @throws UnsupportedException when the server (or a gateway) reports none
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}.
   *
   * @return a future with the version
   */
  public CompletableFuture<String> serverVersionAsync() {
    return serverInfoAsync()
        .thenApply(
            i ->
                i.version()
                    .orElseThrow(
                        () ->
                            new UnsupportedException(
                                "serverVersion: the service does not report a version")));
  }

  /**
   * Starts OpenTelemetry tracing to this Zipkin with the process environment beneath {@code config}
   * (see {@link ZipkinTracing}). The native exporter uses this client's endpoint, headers, TLS,
   * proxy, retries, hooks and logger; an explicit {@code config.endpoint} wins. The handle is shut
   * down by {@link #close()}.
   *
   * @param config tracing config
   * @return the running setup
   */
  public ZipkinTracingHandle initTracing(TracingConfig config) {
    return initTracing(config, ZipkinTracing.Options.builder());
  }

  /**
   * Like {@link #initTracing(TracingConfig)} with options (environment map, protocol, error
   * callback). An explicit {@link ZipkinTracing.Options#clientConfig} wins over this client's
   * config.
   *
   * @param config tracing config
   * @param options options
   * @return the running setup
   * @throws ConfigException when the client is closed or a setting is invalid
   */
  public ZipkinTracingHandle initTracing(TracingConfig config, ZipkinTracing.Options options) {
    if (closed.get()) {
      throw new ConfigException("initTracing: client is closed");
    }
    ZipkinTracingHandle handle =
        ZipkinTracing.initTracing(config, options.defaultClientConfig(this.config));
    register(handle);
    return handle;
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /** Stops registered resources and the transport. Idempotent. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<AutoCloseable> copy;
    synchronized (resources) {
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    transport.close();
  }

  @Override
  public String toString() {
    return "ZipkinClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final ZipkinConfig.Builder config = ZipkinConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the server base URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the span ingest URL (default {@code <url>/api/v2/spans}).
     *
     * @param endpoint URL
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      config.endpoint(endpoint);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Adds headers.
     *
     * @param headers headers
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      config.headers(headers);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public ZipkinClient build() {
      return create(config.build());
    }
  }
}
