package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.ConfigException;
import codes.grow.packages.zipkin.core.HttpTransport;
import codes.grow.packages.zipkin.core.TransportRequest;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Reports spans to Zipkin without OpenTelemetry: {@code POST /api/v2/spans} with a JSON array of v2
 * spans ({@code 202 Accepted}). Spans are normalized locally first ({@link ZipkinModel#normalize}),
 * so an invalid span raises {@link ValidationException} before anything is sent. Ingest is
 * idempotent (Zipkin merges spans by id), so 5xx, 429 and network errors are retried; a 400 or 413
 * is permanent.
 *
 * <p>Get one from {@link ZipkinClient#reporter()} (shares the client's connections) or create one
 * from a config with {@link #create(ZipkinConfig)} (owns its connections; close it).
 *
 * <pre>{@code
 * client.reporter().report(List.of(
 *     ZipkinSpan.builder(traceId, spanId).name("get /orders").kind("server")
 *         .localServiceName("orders").timestamp(Instant.now()).duration(Duration.ofMillis(5))
 *         .tag("http.status_code", 200).build()));
 * }</pre>
 */
public final class ZipkinReporter implements AutoCloseable {
  /** Default number of spans per POST (SPEC batch default). */
  public static final int DEFAULT_MAX_BATCH_SIZE = 512;

  private final HttpTransport transport;
  private final boolean owned;
  private final String endpoint;
  private final Compression compression;
  private final int maxBatchSize;
  private final AtomicBoolean closed = new AtomicBoolean();

  ZipkinReporter(HttpTransport transport, boolean owned, ZipkinConfig config, Options options) {
    String ep = options.endpoint != null ? options.endpoint : config.endpoint();
    if (!isHttpUrl(ep)) {
      if (owned) {
        transport.close();
      }
      throw new ConfigException("zipkin.reporter: endpoint must be an http or https URL");
    }
    this.transport = transport;
    this.owned = owned;
    this.endpoint = ep;
    this.compression = options.compression;
    this.maxBatchSize = options.maxBatchSize;
  }

  private static boolean isHttpUrl(String value) {
    try {
      URI u = new URI(value);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * A standalone reporter with default options (no compression, 512 spans per POST).
   *
   * @param config configuration
   * @return a reporter that owns its connections
   */
  public static ZipkinReporter create(ZipkinConfig config) {
    return create(config, Options.defaults());
  }

  /**
   * A standalone reporter.
   *
   * @param config configuration
   * @param options compression, batch size and endpoint override
   * @return a reporter that owns its connections
   */
  public static ZipkinReporter create(ZipkinConfig config, Options options) {
    config.validate();
    return new ZipkinReporter(new HttpTransport(config.toTransportConfig()), true, config, options);
  }

  /**
   * A standalone reporter from {@code GROW_ZIPKIN_*} / {@code ZIPKIN_URL} / {@code
   * OTEL_EXPORTER_ZIPKIN_ENDPOINT}.
   *
   * @return a reporter that owns its connections
   */
  public static ZipkinReporter fromEnv() {
    return create(ZipkinConfig.fromEnv());
  }

  /**
   * Ingest URL used by {@link #report}.
   *
   * @return the URL
   */
  public String endpoint() {
    return endpoint;
  }

  /**
   * Request body compression.
   *
   * @return {@code GZIP} or {@code NONE}
   */
  public Compression compression() {
    return compression;
  }

  /**
   * Spans per POST.
   *
   * @return the batch size
   */
  public int maxBatchSize() {
    return maxBatchSize;
  }

  /**
   * Sends spans: an empty list sends nothing; more than {@link #maxBatchSize()} spans are sent in
   * several POSTs, in order. Spans are queryable once the call returns (immediately with in-memory
   * storage; persistent backends may lag, use {@link ZipkinQueryClient#waitForTrace}).
   *
   * @param spans spans
   * @throws ValidationException for an invalid span (nothing sent) or a 400/413 answer
   */
  public void report(List<ZipkinSpan> spans) {
    HttpTransport.await(reportAsync(spans), "zipkin.report");
  }

  /**
   * Asynchronous {@link #report}. Cancelling the future aborts the call in flight.
   *
   * @param spans spans
   * @return a future completed when every batch was accepted
   */
  public CompletableFuture<Void> reportAsync(List<ZipkinSpan> spans) {
    return ZipkinHttp.guard(
        () -> {
          List<ZipkinSpan> normalized = new ArrayList<>(spans.size());
          for (ZipkinSpan s : spans) {
            normalized.add(ZipkinModel.normalize(s, "zipkin.report"));
          }
          CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
          for (int i = 0; i < normalized.size(); i += maxBatchSize) {
            List<ZipkinSpan> batch =
                normalized.subList(i, Math.min(normalized.size(), i + maxBatchSize));
            chain = chain.thenCompose(v -> send(batch));
          }
          return chain;
        });
  }

  private CompletableFuture<Void> send(List<ZipkinSpan> batch) {
    byte[] json = ZipkinModel.toJson(batch).getBytes(StandardCharsets.UTF_8);
    TransportRequest.Builder req =
        TransportRequest.post(endpoint).operation("zipkin.report").idempotent(true);
    if (compression == Compression.GZIP) {
      req.body(gzip(json), "application/json").header("Content-Encoding", "gzip");
    } else {
      req.body(json, "application/json");
    }
    return ZipkinHttp.send(transport, req, false).thenApply(r -> null);
  }

  static byte[] gzip(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Releases connections when this reporter owns them (no-op for a client's). Idempotent. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && owned) {
      transport.close();
    }
  }

  @Override
  public String toString() {
    return "ZipkinReporter{endpoint="
        + codes.grow.packages.zipkin.core.Redaction.redactUrl(endpoint)
        + ", compression="
        + compression.wireValue()
        + ", maxBatchSize="
        + maxBatchSize
        + "}";
  }

  /** Options of a {@link ZipkinReporter}. */
  public static final class Options {
    private Compression compression = Compression.NONE;
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private String endpoint;

    private Options() {}

    /**
     * The defaults: no compression, 512 spans per POST, the config's endpoint.
     *
     * @return options
     */
    public static Options defaults() {
      return new Options();
    }

    /**
     * Request body compression ({@code GZIP} sends {@code Content-Encoding: gzip}).
     *
     * @param compression compression
     * @return this
     */
    public Options compression(Compression compression) {
      if (compression == null) {
        throw new ConfigException("zipkin.reporter: compression must be gzip or none");
      }
      this.compression = compression;
      return this;
    }

    /**
     * Spans per POST; larger reports are split.
     *
     * @param maxBatchSize positive size
     * @return this
     */
    public Options maxBatchSize(int maxBatchSize) {
      if (maxBatchSize <= 0) {
        throw new ConfigException("zipkin.reporter: maxBatchSize must be a positive integer");
      }
      this.maxBatchSize = maxBatchSize;
      return this;
    }

    /**
     * Ingest URL; default the config's endpoint.
     *
     * @param endpoint {@code http} or {@code https} URL
     * @return this
     */
    public Options endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }
  }
}
