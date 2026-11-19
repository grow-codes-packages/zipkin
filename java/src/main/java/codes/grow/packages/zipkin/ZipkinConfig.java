package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.core.Hooks;
import codes.grow.packages.zipkin.core.Logger;
import codes.grow.packages.zipkin.core.Redaction;
import codes.grow.packages.zipkin.core.RetryPolicy;
import codes.grow.packages.zipkin.core.TlsOptions;
import codes.grow.packages.zipkin.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration of a {@link ZipkinClient} (SPEC section 2.2): the Zipkin server URL (port
 * 9411), the span ingest endpoint and the common transport settings.
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; SDK variable ({@code GROW_ZIPKIN_*}) &gt; vendor
 * variables &gt; default:
 *
 * <ul>
 *   <li>{@code url}: {@code GROW_ZIPKIN_URL} &gt; {@code ZIPKIN_URL} &gt; {@code
 *       OTEL_EXPORTER_ZIPKIN_ENDPOINT} without its trailing {@code /api/v2/spans} &gt; {@value
 *       #DEFAULT_URL}.
 *   <li>{@code endpoint}: {@code GROW_ZIPKIN_ENDPOINT} &gt; {@code OTEL_EXPORTER_ZIPKIN_ENDPOINT}
 *       (used verbatim) &gt; {@code <url>/api/v2/spans}.
 *   <li>timeouts, retries, TLS and proxy: the common {@code GROW_ZIPKIN_*} variables.
 * </ul>
 *
 * <p>Stock Zipkin has no authentication. Behind a gateway, put credentials in {@link
 * Builder#header} (sensitive headers are redacted everywhere). {@link #toString()} never prints
 * credentials.
 */
public final class ZipkinConfig {
  /** Default server URL. */
  public static final String DEFAULT_URL = "http://127.0.0.1:9411";

  /** Path of the Zipkin v2 span ingest API. */
  public static final String SPANS_PATH = "/api/v2/spans";

  /** Vendor variables read for {@code url} after the SDK variable {@code URL}, in order. */
  static final String[] URL_VENDOR_VARS = {"ZIPKIN_URL"};

  /** Vendor variables read for {@code endpoint} after the SDK variable {@code ENDPOINT}. */
  static final String[] ENDPOINT_VENDOR_VARS = {"OTEL_EXPORTER_ZIPKIN_ENDPOINT"};

  /**
   * Name that no environment variable can have ({@code =} is not allowed in names): reading it with
   * vendor names returns only the vendor variables.
   */
  private static final String VENDOR_ONLY = "=";

  /** Vendor variables read for the CA bundle after the SDK variable {@code CA_CERT}. */
  static final String[] CA_CERT_VENDOR_VARS = {};

  private final String url;
  private final String endpoint;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private ZipkinConfig(Builder b) {
    this.url = b.url != null && !b.url.isEmpty() ? b.url : DEFAULT_URL;
    this.endpoint = b.endpoint != null && !b.endpoint.isEmpty() ? b.endpoint : spansEndpoint(url);
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder pre-filled with this configuration.
   *
   * @return builder copy
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.url = url;
    b.endpoint = endpoint;
    b.connectTimeout = connectTimeout;
    b.requestTimeout = requestTimeout;
    b.totalTimeout = totalTimeout;
    b.retry = retry;
    b.tls = tls;
    b.proxy = proxy;
    b.headers.putAll(headers);
    b.userAgentSuffix = userAgentSuffix;
    b.hooks = hooks;
    b.logger = logger;
    return b;
  }

  /**
   * {@code <base>/api/v2/spans}, appended once: a base that already ends with the spans path is
   * kept, a gateway prefix is preserved, trailing slashes are dropped.
   *
   * @param base server base URL
   * @return the ingest URL
   */
  public static String spansEndpoint(String base) {
    String trimmed = base.replaceAll("/+$", "");
    return trimmed.endsWith(SPANS_PATH) ? trimmed : trimmed + SPANS_PATH;
  }

  /**
   * The server base URL of a spans endpoint: strips one trailing {@code /api/v2/spans} (and
   * slashes).
   *
   * @param endpoint ingest URL
   * @return the base URL
   */
  public static String baseFromEndpoint(String endpoint) {
    String trimmed = endpoint.replaceAll("/+$", "");
    return trimmed.endsWith(SPANS_PATH)
        ? trimmed.substring(0, trimmed.length() - SPANS_PATH.length())
        : trimmed;
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value)
   */
  public static ZipkinConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static ZipkinConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Checks URL scheme, positive timeouts, retry limits and TLS pairs.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    checkUrl("url", url);
    checkUrl("endpoint", endpoint);
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        URI p = new URI(proxy);
        if (p.getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
  }

  private static void checkUrl(String name, String value) {
    URI u;
    try {
      u = new URI(value);
    } catch (URISyntaxException e) {
      throw new ConfigException("config: " + name + " is not a valid URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null) {
      throw new ConfigException("config: " + name + " must use http or https and include a host");
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Translates the common fields into transport settings.
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    return TransportConfig.builder(url)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(headers)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Credential values to redact from error messages and logs (sensitive header values).
   *
   * @return the values
   */
  public List<String> knownSecrets() {
    List<String> out = new ArrayList<>();
    headers.forEach(
        (name, value) -> {
          if (Redaction.isSensitiveHeader(name) && value != null && !value.isEmpty()) {
            out.add(value);
          }
        });
    return out;
  }

  /**
   * Returns the server base URL.
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * Returns the span ingest URL used by {@link ZipkinReporter} and the tracing exporter.
   *
   * @return {@code <url>/api/v2/spans} unless set
   */
  public String endpoint() {
    return endpoint;
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return "ZipkinConfig{url="
        + Redaction.redactUrl(url)
        + ", endpoint="
        + Redaction.redactUrl(endpoint)
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private String endpoint;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment.
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url =
            env.string("URL", URL_VENDOR_VARS)
                .or(
                    () ->
                        env.string(VENDOR_ONLY, ENDPOINT_VENDOR_VARS)
                            .map(ZipkinConfig::baseFromEndpoint))
                .orElse(null);
      }
      if (endpoint == null) {
        endpoint = env.string("ENDPOINT", ENDPOINT_VENDOR_VARS).orElse(null);
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT", CA_CERT_VENDOR_VARS).ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT").ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY").ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY").ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the server base URL (default {@value ZipkinConfig#DEFAULT_URL}). A gateway path prefix
     * is kept ({@code https://gw.example/zipkin} → {@code https://gw.example/zipkin/api/v2/...}).
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the full span ingest URL (default {@code <url>/api/v2/spans}).
     *
     * @param endpoint {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request (gateway credentials go here; sensitive values are
     * redacted).
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Adds headers sent with every request.
     *
     * @param headers headers
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      this.headers.putAll(headers);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public ZipkinConfig build() {
      ZipkinConfig c = new ZipkinConfig(this);
      c.validate();
      return c;
    }
  }
}
