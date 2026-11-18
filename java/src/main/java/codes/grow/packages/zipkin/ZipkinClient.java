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
import codes.grow.packages.zipkin.core.Secret;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.core.TransportRequest;
import codes.grow.packages.zipkin.core.TransportResponse;
import codes.grow.packages.zipkin.core.UnsupportedException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for Zipkin.
 *
 * <p>Implements SPEC section 2.4. Thread-safe; create one per endpoint and close it when done.
 *
 * <pre>{@code
 * try (ZipkinClient client = ZipkinClient.fromEnv()) {
 *   HealthStatus health = client.health();
 * }
 * }</pre>
 *
 * <p>Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}. This file is owned by the service package (generated once by sdk-kit); add
 * service modules here.
 */
public final class ZipkinClient implements AutoCloseable {
  private final ZipkinConfig config;
  private final HttpTransport transport;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();

  private ZipkinClient(ZipkinConfig config) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
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
   * Creates a client from the SDK and vendor environment variables.
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
   * Checks liveness ({@code GET /health}). Never throws for an unhealthy or unreachable service.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HealthCheck.check(transport, "/health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    return HealthCheck.checkAsync(transport, "/health");
  }

  /**
   * Performs an authenticated round trip ({@code GET /v1/ping}).
   *
   * @return the latency
   * @throws AuthenticationException on bad credentials
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
    return transport
        .sendAsync(authorized(TransportRequest.get("/v1/ping").operation("ping")))
        .thenApply(r -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * Returns the server version ({@code GET /version}).
   *
   * @return the version string
   * @throws UnsupportedException when the server exposes none
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
    return transport
        .sendAsync(
            authorized(
                TransportRequest.get("/version").operation("serverVersion").acceptStatus(404)))
        .thenApply(ZipkinClient::version);
  }

  private static String version(TransportResponse r) {
    JsonNode v = r.status() == 404 ? null : r.jsonNode().get("version");
    if (v == null || !v.isTextual()) {
      throw new UnsupportedException("serverVersion: the server exposes no version");
    }
    return v.asText();
  }

  private TransportRequest authorized(TransportRequest.Builder b) {
    config.token().ifPresent(t -> b.header("Authorization", "Bearer " + t.reveal()));
    return b.build();
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
     * Sets the endpoint base URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the API token.
     *
     * @param token token
     * @return this builder
     */
    public Builder token(String token) {
      config.token(token);
      return this;
    }

    /**
     * Sets the API token.
     *
     * @param token token
     * @return this builder
     */
    public Builder token(Secret token) {
      config.token(token);
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
