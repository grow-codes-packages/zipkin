package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.EnvReader;
import codes.grow.packages.zipkin.tracing.OtlpExporters;
import codes.grow.packages.zipkin.tracing.SpanExporterFactory;
import codes.grow.packages.zipkin.tracing.Tracing;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import codes.grow.packages.zipkin.tracing.TracingConfig.Protocol;
import codes.grow.packages.zipkin.tracing.TracingConfig.SamplerType;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Zipkin tracing preset (SPEC section 5): OpenTelemetry tracing that exports Zipkin v2 JSON to
 * {@code POST /api/v2/spans} through {@link ZipkinSpanExporter} ({@code native}, the default) or,
 * opt-in, OTLP to an OpenTelemetry Collector whose {@code zipkin} exporter forwards to Zipkin
 * ({@code grpc} / {@code http/protobuf}; stock Zipkin does not accept OTLP).
 *
 * <pre>{@code
 * try (ZipkinTracingHandle tracing = ZipkinTracing.initTracing(
 *     TracingConfig.builder("orders").setGlobal(false).build())) {
 *   Spans.withSpan("checkout", () -> doWork());
 * }
 * }</pre>
 *
 * <p><b>Defaults.</b> Protocol {@code native} to {@code http://127.0.0.1:9411/api/v2/spans},
 * propagators {@code tracecontext, baggage, b3} composed B3-first so W3C {@code traceparent} wins
 * when both disagree ({@link #orderPropagators}), gzip bodies.
 *
 * <p><b>Environment.</b> {@link #initTracing(TracingConfig)} fills every field the config leaves at
 * its generic default from the environment; explicit values win. Java configs cannot tell an unset
 * field from one set to its default, so a config with the generic default protocol ({@code
 * http/protobuf}) counts as "unset" and gets {@code GROW_ZIPKIN_PROTOCOL} or {@code native}: choose
 * OTLP over HTTP with {@link Options#protocol} (or the variable). Likewise the generic default
 * propagators ({@code tracecontext, baggage}) become {@code OTEL_PROPAGATORS} or the Zipkin
 * default.
 *
 * <table>
 *   <caption>Environment variables</caption>
 *   <tr><th>Field</th><th>Variables</th></tr>
 *   <tr><td>protocol</td><td>{@code GROW_ZIPKIN_PROTOCOL} ({@code native}, {@code grpc}, {@code
 *       http/protobuf})</td></tr>
 *   <tr><td>endpoint (native)</td><td>{@code GROW_ZIPKIN_ENDPOINT}, {@code
 *       OTEL_EXPORTER_ZIPKIN_ENDPOINT} (as is), {@code GROW_ZIPKIN_URL} / {@code ZIPKIN_URL} +
 *       {@code /api/v2/spans}</td></tr>
 *   <tr><td>endpoint (OTLP, a Collector)</td><td>{@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} (as
 *       is), {@code OTEL_EXPORTER_OTLP_ENDPOINT} (HTTP appends {@code /v1/traces})</td></tr>
 *   <tr><td>export timeout</td><td>{@code OTEL_EXPORTER_ZIPKIN_TIMEOUT} (ms or {@code
 *       10s})</td></tr>
 *   <tr><td>compression</td><td>{@code GROW_ZIPKIN_COMPRESSION} ({@code gzip}, {@code
 *       none})</td></tr>
 *   <tr><td>service name</td><td>{@code OTEL_SERVICE_NAME}, else {@code service.name} in {@code
 *       OTEL_RESOURCE_ATTRIBUTES} ({@link #tracingConfigFromEnv} only)</td></tr>
 *   <tr><td>resource attributes</td><td>{@code OTEL_RESOURCE_ATTRIBUTES}</td></tr>
 *   <tr><td>sampler</td><td>{@code OTEL_TRACES_SAMPLER}, {@code OTEL_TRACES_SAMPLER_ARG}</td></tr>
 *   <tr><td>propagators</td><td>{@code OTEL_PROPAGATORS}</td></tr>
 * </table>
 *
 * <p>Headers come from code only (no credential variables exist). A present but invalid value
 * raises {@link ConfigException} naming the variable, never its value. The connection settings of
 * the native exporter (proxy, TLS, retries, hooks, logger, headers) come from {@link
 * ZipkinConfig#fromEnv()} unless {@link Options#clientConfig} is given.
 */
public final class ZipkinTracing {
  /** Default protocol of the preset: Zipkin v2 JSON to {@code /api/v2/spans}. */
  public static final Protocol DEFAULT_PROTOCOL = Protocol.NATIVE;

  /**
   * Default propagators of the preset: W3C trace context + baggage and B3 single-header ({@code
   * b3}). Add {@code b3multi} to also inject the {@code X-B3-*} headers; every B3 propagator
   * extracts both B3 forms.
   */
  public static final List<String> DEFAULT_PROPAGATORS = List.of("tracecontext", "baggage", "b3");

  private static final Set<String> B3 = Set.of("b3", "b3multi");
  private static final Duration DEFAULT_EXPORT_TIMEOUT =
      TracingConfig.BatchSettings.defaults().exportTimeout();

  private ZipkinTracing() {}

  // ---------------------------------------------------------------------------------------------
  // Helpers

  /**
   * The order in which propagators are composed: B3 ({@code b3}, {@code b3multi}) first, every
   * other propagator after it in the given order. On extract, later propagators overwrite earlier
   * ones, so W3C {@code traceparent} wins when it and B3 disagree. Names are trimmed and
   * lower-cased; duplicates are removed.
   *
   * @param names propagator names
   * @return the ordered names
   */
  public static List<String> orderPropagators(List<String> names) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String n : names) {
      normalized.add(n.trim().toLowerCase(Locale.ROOT));
    }
    List<String> out = new ArrayList<>();
    for (String n : normalized) {
      if (B3.contains(n)) {
        out.add(n);
      }
    }
    for (String n : normalized) {
      if (!B3.contains(n)) {
        out.add(n);
      }
    }
    return List.copyOf(out);
  }

  /**
   * The OTLP endpoint for a protocol: for {@code http/protobuf}, an endpoint without a path gets
   * {@code /v1/traces}; any other endpoint is used as is.
   *
   * @param protocol protocol
   * @param endpoint endpoint
   * @return the exporter URL
   */
  public static String resolveOtlpEndpoint(Protocol protocol, String endpoint) {
    if (protocol == Protocol.HTTP_PROTOBUF && !hasPath(endpoint)) {
      return endpoint.replaceAll("/+$", "") + "/v1/traces";
    }
    return endpoint;
  }

  private static boolean hasPath(String url) {
    try {
      String path = URI.create(url).getPath();
      return path != null && !path.isEmpty() && !path.equals("/");
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  private static String[] hit(Map<String, String> env, String... names) {
    for (String name : names) {
      String v = env.get(name);
      if (v != null && !v.isBlank()) {
        return new String[] {name, v.trim()};
      }
    }
    return null;
  }

  private static ConfigException invalid(String variable, String expected) {
    return new ConfigException(
        "tracing: invalid value in environment variable "
            + variable
            + " (expected "
            + expected
            + ")");
  }

  static Map<String, String> parseKeyValues(String raw, String variable) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : raw.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      int eq = part.indexOf('=');
      if (eq <= 0 || part.substring(0, eq).isBlank()) {
        throw invalid(variable, "key=value pairs");
      }
      try {
        out.put(
            URLDecoder.decode(part.substring(0, eq).trim(), StandardCharsets.UTF_8),
            URLDecoder.decode(part.substring(eq + 1).trim(), StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        throw invalid(variable, "valid percent-encoding");
      }
    }
    return out;
  }

  private static Protocol parseProtocol(String[] hit) {
    switch (hit[1].toLowerCase(Locale.ROOT)) {
      case "native":
        return Protocol.NATIVE;
      case "grpc":
        return Protocol.GRPC;
      case "http/protobuf":
        return Protocol.HTTP_PROTOBUF;
      default:
        throw invalid(hit[0], "native, grpc or http/protobuf");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration

  /**
   * The service name from the environment: {@code OTEL_SERVICE_NAME}, else {@code service.name} in
   * {@code OTEL_RESOURCE_ATTRIBUTES}.
   *
   * @param env variables
   * @return the name, or empty
   */
  public static Optional<String> serviceNameFromEnv(Map<String, String> env) {
    String[] name = hit(env, "OTEL_SERVICE_NAME");
    if (name != null) {
      return Optional.of(name[1]);
    }
    String[] attrs = hit(env, "OTEL_RESOURCE_ATTRIBUTES");
    if (attrs != null) {
      String fromAttrs = parseKeyValues(attrs[1], attrs[0]).get("service.name");
      if (fromAttrs != null && !fromAttrs.isBlank()) {
        return Optional.of(fromAttrs);
      }
    }
    return Optional.empty();
  }

  /**
   * Builds a tracing config from the process environment (see the class description).
   *
   * @param serviceName service name, or {@code null} to take it from the environment
   * @return the config
   * @throws ConfigException when no service name is configured or a variable is invalid
   */
  public static TracingConfig tracingConfigFromEnv(String serviceName) {
    return tracingConfigFromEnv(serviceName, System.getenv());
  }

  /**
   * Builds a tracing config from a variable map (see the class description).
   *
   * @param serviceName service name, or {@code null}/blank to take it from the environment
   * @param env variables
   * @return the config
   * @throws ConfigException when no service name is configured or a variable is invalid
   */
  public static TracingConfig tracingConfigFromEnv(String serviceName, Map<String, String> env) {
    String name = serviceName;
    if (name == null || name.isBlank()) {
      name =
          serviceNameFromEnv(env)
              .orElseThrow(
                  () ->
                      new ConfigException(
                          "tracing: no service name (set OTEL_SERVICE_NAME or pass one)"));
    }
    return resolveConfig(TracingConfig.builder(name).build(), env, null);
  }

  /**
   * Fills every field of {@code explicit} that is still at its generic default from {@code env} and
   * applies the Zipkin defaults (see the class description). The propagators of the result are
   * ordered with {@link #orderPropagators}.
   *
   * @param explicit explicit config
   * @param env variables (an empty map applies only the Zipkin defaults)
   * @param protocol explicit protocol, or {@code null}
   * @return the resolved config
   * @throws ConfigException when a variable is invalid
   */
  public static TracingConfig resolveConfig(
      TracingConfig explicit, Map<String, String> env, Protocol protocol) {
    TracingConfig.Builder b = explicit.toBuilder();
    Protocol p = protocol;
    if (p == null) {
      if (explicit.protocol() != Protocol.HTTP_PROTOBUF) {
        p = explicit.protocol();
      } else {
        String[] hit = hit(env, EnvReader.DEFAULT_PREFIX + "PROTOCOL");
        p = hit == null ? DEFAULT_PROTOCOL : parseProtocol(hit);
      }
    }
    b.protocol(p);

    if (explicit.endpoint().isEmpty()) {
      if (p == Protocol.NATIVE) {
        String[] endpoint =
            hit(env, EnvReader.DEFAULT_PREFIX + "ENDPOINT", "OTEL_EXPORTER_ZIPKIN_ENDPOINT");
        String[] url = hit(env, EnvReader.DEFAULT_PREFIX + "URL", "ZIPKIN_URL");
        if (endpoint != null) {
          b.endpoint(endpoint[1]);
        } else if (url != null) {
          b.endpoint(ZipkinConfig.spansEndpoint(url[1]));
        }
      } else {
        String[] traces = hit(env, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        String[] base = hit(env, "OTEL_EXPORTER_OTLP_ENDPOINT");
        if (traces != null) {
          b.endpoint(traces[1]);
        } else if (base != null) {
          b.endpoint(resolveOtlpEndpoint(p, base[1]));
        }
      }
    }

    TracingConfig.BatchSettings batch = explicit.batch();
    String[] timeout = hit(env, "OTEL_EXPORTER_ZIPKIN_TIMEOUT");
    if (timeout != null && batch.exportTimeout().equals(DEFAULT_EXPORT_TIMEOUT)) {
      Duration d =
          EnvReader.parseDuration(timeout[1])
              .filter(x -> !x.isZero() && !x.isNegative())
              .orElseThrow(() -> invalid(timeout[0], "a duration such as 10000 or 10s"));
      b.batch(
          new TracingConfig.BatchSettings(
              batch.maxQueueSize(), batch.maxExportBatchSize(), batch.scheduledDelay(), d));
    }

    String[] compression = hit(env, EnvReader.DEFAULT_PREFIX + "COMPRESSION");
    if (compression != null && explicit.compression() == Compression.GZIP) {
      switch (compression[1].toLowerCase(Locale.ROOT)) {
        case "gzip":
          b.compression(Compression.GZIP);
          break;
        case "none":
          b.compression(Compression.NONE);
          break;
        default:
          throw invalid(compression[0], "gzip or none");
      }
    }

    String[] resource = hit(env, "OTEL_RESOURCE_ATTRIBUTES");
    if (resource != null) {
      Map<String, Object> attrs = new LinkedHashMap<>(parseKeyValues(resource[1], resource[0]));
      attrs.remove("service.name");
      if (explicit.resourceAttributes().isEmpty()) {
        b.resourceAttributes(attrs);
      }
    }

    String[] sampler = hit(env, "OTEL_TRACES_SAMPLER");
    if (sampler != null
        && explicit.sampler() == SamplerType.PARENTBASED_ALWAYS_ON
        && explicit.samplerRatio() == 1.0) {
      String name = sampler[1].toLowerCase(Locale.ROOT);
      SamplerType type = null;
      for (SamplerType t : SamplerType.values()) {
        if (t.wireValue().equals(name)) {
          type = t;
        }
      }
      if (type == null) {
        throw invalid(sampler[0], "a known sampler");
      }
      b.sampler(type);
      String[] arg = hit(env, "OTEL_TRACES_SAMPLER_ARG");
      if (name.endsWith("traceidratio") && arg != null) {
        double ratio;
        try {
          ratio = Double.parseDouble(arg[1]);
        } catch (NumberFormatException ex) {
          ratio = -1;
        }
        if (!(ratio >= 0 && ratio <= 1)) {
          throw invalid(arg[0], "a ratio between 0 and 1");
        }
        b.samplerRatio(ratio);
      }
    }

    List<String> propagators = explicit.propagators();
    if (propagators.equals(TracingConfig.DEFAULT_PROPAGATORS)) {
      propagators = DEFAULT_PROPAGATORS;
      String[] env1 = hit(env, "OTEL_PROPAGATORS");
      if (env1 != null) {
        List<String> list = new ArrayList<>();
        for (String s : env1[1].split(",")) {
          if (!s.isBlank()) {
            list.add(s.trim());
          }
        }
        propagators = list;
      }
    }
    b.propagators(orderPropagators(propagators));
    return b.build();
  }

  // ---------------------------------------------------------------------------------------------
  // Exporter

  /**
   * The span exporter factory of the preset.
   *
   * <ul>
   *   <li>{@code native} → {@link ZipkinSpanExporter} to {@code config.endpoint} (else the client
   *       config's endpoint). Headers = client headers + tracing headers; TLS = tracing TLS, else
   *       client TLS; request and total timeout = the batch export timeout; compression from the
   *       config; proxy, retries, hooks and logger from the client config.
   *   <li>{@code http/protobuf} / {@code grpc} → the official OTLP exporter to an OpenTelemetry
   *       Collector ({@code config.endpoint} is required; the Collector's {@code zipkin} exporter
   *       forwards to Zipkin).
   * </ul>
   *
   * @param clientConfig connection settings, or {@code null} for the defaults
   * @param onExportError called with every failed native export, or {@code null}
   * @return factory
   */
  public static SpanExporterFactory exporterFactory(
      ZipkinConfig clientConfig, Consumer<Throwable> onExportError) {
    ZipkinConfig base = clientConfig != null ? clientConfig : ZipkinConfig.builder().build();
    return config -> {
      if (config.protocol() != Protocol.NATIVE) {
        String endpoint = config.endpoint().orElse(null);
        if (endpoint == null || endpoint.replaceAll("/+$", "").endsWith(ZipkinConfig.SPANS_PATH)) {
          throw new ConfigException(
              "tracing: protocol grpc/http/protobuf needs the endpoint of an OpenTelemetry"
                  + " Collector with a zipkin exporter (stock Zipkin does not accept OTLP)");
        }
        return OtlpExporters.create(
            config.toBuilder().endpoint(resolveOtlpEndpoint(config.protocol(), endpoint)).build());
      }
      Duration timeout = config.batch().exportTimeout();
      ZipkinConfig.Builder cb =
          base.toBuilder()
              .endpoint(config.endpoint().orElse(base.endpoint()))
              .requestTimeout(timeout)
              .totalTimeout(timeout)
              .headers(config.headers());
      config.tls().ifPresent(cb::tls);
      return ZipkinSpanExporter.builder()
          .config(cb.build())
          .compression(config.compression())
          .maxBatchSize(config.batch().maxExportBatchSize())
          .onError(onExportError)
          .build();
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Setup

  /** Options of {@link #initTracing(TracingConfig, Options)}. */
  public static final class Options {
    private Map<String, String> env;
    private boolean ignoreEnv;
    private ZipkinConfig clientConfig;
    private Consumer<Throwable> onExportError;
    private Protocol protocol;

    private Options() {}

    /**
     * Starts empty options (process environment, connection settings from it).
     *
     * @return options
     */
    public static Options builder() {
      return new Options();
    }

    /**
     * Reads this variable map instead of the process environment.
     *
     * @param env variables
     * @return this
     */
    public Options env(Map<String, String> env) {
      this.env = env;
      return this;
    }

    /**
     * Ignores the environment: only the config and the Zipkin defaults apply.
     *
     * @return this
     */
    public Options ignoreEnv() {
      this.ignoreEnv = true;
      return this;
    }

    /**
     * Connection settings of the native exporter (proxy, TLS, retries, hooks, logger, headers,
     * endpoint). Default {@link ZipkinConfig#fromEnv()} (or the defaults with {@link
     * #ignoreEnv()}).
     *
     * @param config configuration
     * @return this
     */
    public Options clientConfig(ZipkinConfig config) {
      this.clientConfig = config;
      return this;
    }

    /**
     * Called with every failed native export (the batch is dropped).
     *
     * @param onExportError callback
     * @return this
     */
    public Options onExportError(Consumer<Throwable> onExportError) {
      this.onExportError = onExportError;
      return this;
    }

    /**
     * Explicit protocol (wins over the config and {@code GROW_ZIPKIN_PROTOCOL}); the way to select
     * {@code http/protobuf} in code.
     *
     * @param protocol protocol
     * @return this
     */
    public Options protocol(Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    Options defaultClientConfig(ZipkinConfig config) {
      if (clientConfig == null) {
        clientConfig = config;
      }
      return this;
    }

    Map<String, String> effectiveEnv() {
      return ignoreEnv ? Map.of() : env != null ? env : System.getenv();
    }
  }

  /**
   * Starts tracing with the process environment beneath {@code config} (see the class description).
   *
   * @param config tracing config
   * @return the running setup; shut it down before exit
   */
  public static ZipkinTracingHandle initTracing(TracingConfig config) {
    return initTracing(config, Options.builder());
  }

  /**
   * Starts tracing with the service name and every setting from the process environment.
   *
   * @param serviceName service name, or {@code null} for {@code OTEL_SERVICE_NAME}
   * @return the running setup
   */
  public static ZipkinTracingHandle initTracingFromEnv(String serviceName) {
    return initTracingFromEnv(serviceName, System.getenv());
  }

  /**
   * Starts tracing with the service name and every setting from a variable map.
   *
   * @param serviceName service name, or {@code null} for {@code OTEL_SERVICE_NAME}
   * @param env variables
   * @return the running setup
   */
  public static ZipkinTracingHandle initTracingFromEnv(
      String serviceName, Map<String, String> env) {
    return initTracing(tracingConfigFromEnv(serviceName, env), Options.builder().env(env));
  }

  /**
   * Starts tracing.
   *
   * @param config tracing config
   * @param options environment, connection settings, protocol and error callback
   * @return the running setup; shut it down before exit
   * @throws ConfigException for invalid settings
   */
  public static ZipkinTracingHandle initTracing(TracingConfig config, Options options) {
    Map<String, String> env = options.effectiveEnv();
    TracingConfig resolved = resolveConfig(config, env, options.protocol);
    ZipkinConfig base =
        options.clientConfig != null
            ? options.clientConfig
            : options.ignoreEnv ? ZipkinConfig.builder().build() : ZipkinConfig.fromEnv(env);
    if (resolved.endpoint().isEmpty() && resolved.protocol() == Protocol.NATIVE) {
      resolved = resolved.toBuilder().endpoint(base.endpoint()).build();
    }
    return new ZipkinTracingHandle(
        Tracing.initWith(resolved, exporterFactory(base, options.onExportError)),
        resolved.endpoint().orElse(""));
  }
}
