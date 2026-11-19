package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.tracing.TracingConfig.Compression;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * OpenTelemetry {@link SpanExporter} for Zipkin: maps each span with {@link #toZipkinSpan} and
 * sends the batch with a {@link ZipkinReporter} ({@code POST /api/v2/spans}, JSON v2, gzip by
 * default, retries on 5xx/429/network errors). A failed batch reports a failure to the span
 * processor and is dropped. It does not depend on the OpenTelemetry Zipkin exporter (no longer
 * published for Java).
 */
public final class ZipkinSpanExporter implements SpanExporter {
  /** Service name used when the resource has no {@code service.name}. */
  public static final String UNKNOWN_SERVICE = "unknown_service";

  /** Span attributes that name the remote service of a CLIENT/PRODUCER span, by precedence. */
  public static final List<String> REMOTE_SERVICE_KEYS =
      List.of("peer.service", "server.address", "net.peer.name", "network.peer.address");

  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

  private final ZipkinReporter reporter;
  private final boolean owned;
  private final Consumer<Throwable> onError;
  private final Set<CompletableResultCode> pending = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean shutdown = new AtomicBoolean();

  private ZipkinSpanExporter(Builder b) {
    if (b.reporter != null) {
      this.reporter = b.reporter;
      this.owned = false;
    } else {
      ZipkinConfig config = b.config != null ? b.config : ZipkinConfig.fromEnv();
      ZipkinReporter.Options options = ZipkinReporter.Options.defaults().compression(b.compression);
      if (b.maxBatchSize != null) {
        options.maxBatchSize(b.maxBatchSize);
      }
      this.reporter = ZipkinReporter.create(config, options);
      this.owned = true;
    }
    this.onError = b.onError;
  }

  /**
   * Starts a builder (default: an owned reporter from {@link ZipkinConfig#fromEnv()}, gzip).
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * An exporter that sends through {@code reporter} (not closed by {@link #shutdown()}).
   *
   * @param reporter reporter
   * @return exporter
   */
  public static ZipkinSpanExporter create(ZipkinReporter reporter) {
    return builder().reporter(reporter).build();
  }

  /**
   * The reporter used to send spans.
   *
   * @return the reporter
   */
  public ZipkinReporter reporter() {
    return reporter;
  }

  /**
   * Maps a finished OpenTelemetry span to a Zipkin v2 span (identical in every language of this
   * SDK; based on the OpenTelemetry Zipkin exporter specification).
   *
   * <table>
   *   <caption>Mapping</caption>
   *   <tr><th>Zipkin</th><th>OpenTelemetry</th></tr>
   *   <tr><td>{@code traceId}, {@code id}</td><td>span context ids (lower-case hex, 32 /
   *       16)</td></tr>
   *   <tr><td>{@code parentId}</td><td>parent span id when valid, else omitted</td></tr>
   *   <tr><td>{@code kind}</td><td>SERVER/CLIENT/PRODUCER/CONSUMER 1:1; INTERNAL →
   *       omitted</td></tr>
   *   <tr><td>{@code name}</td><td>span name (the server lower-cases it)</td></tr>
   *   <tr><td>{@code timestamp}</td><td>start time, epoch µs (truncated)</td></tr>
   *   <tr><td>{@code duration}</td><td>end − start in µs, rounded, at least 1</td></tr>
   *   <tr><td>{@code localEndpoint.serviceName}</td><td>resource {@code service.name}, else
   *       {@value #UNKNOWN_SERVICE}</td></tr>
   *   <tr><td>{@code remoteEndpoint}</td><td>CLIENT/PRODUCER only: first non-empty string of
   *       {@code peer.service}, {@code server.address}, {@code net.peer.name}, {@code
   *       network.peer.address}; omitted when none is set</td></tr>
   *   <tr><td>{@code tags}</td><td>span attributes (strings as-is, numbers and booleans in decimal
   *       text, arrays JSON); then {@code otel.scope.name} / {@code otel.scope.version}; {@code
   *       otel.dropped_attributes_count} / {@code otel.dropped_events_count} when &gt; 0; status
   *       ERROR → {@code otel.status_code=ERROR} + {@code error=<message or "">}, OK → {@code
   *       otel.status_code=OK}. Resource attributes are not copied</td></tr>
   *   <tr><td>{@code annotations}</td><td>events: event time µs; value = event name, or the
   *       JSON text {@code {"<name>":{…attributes}}} when the event has attributes</td></tr>
   *   <tr><td>{@code debug}, {@code shared}</td><td>never set</td></tr>
   * </table>
   *
   * <p>Links, trace state and resource attributes other than {@code service.name} are not mapped.
   *
   * @param span finished span
   * @return the Zipkin span
   */
  public static ZipkinSpan toZipkinSpan(SpanData span) {
    SpanContext ctx = span.getSpanContext();
    ZipkinSpan.Builder b =
        ZipkinSpan.builder(
            ctx.getTraceId().toLowerCase(Locale.ROOT), ctx.getSpanId().toLowerCase(Locale.ROOT));
    SpanContext parent = span.getParentSpanContext();
    if (parent != null && parent.isValid()) {
      b.parentId(parent.getSpanId().toLowerCase(Locale.ROOT));
    }
    ZipkinKind kind = kind(span.getKind());
    b.kind(kind).name(span.getName());
    long start = span.getStartEpochNanos();
    b.timestamp(start / 1_000L);
    b.duration(Math.max(1L, Math.round((span.getEndEpochNanos() - start) / 1000.0)));
    String service = span.getResource().getAttribute(SERVICE_NAME);
    b.localServiceName(service == null || service.isEmpty() ? UNKNOWN_SERVICE : service);
    Attributes attributes = span.getAttributes();
    if (kind == ZipkinKind.CLIENT || kind == ZipkinKind.PRODUCER) {
      String remote = remoteServiceName(attributes);
      if (remote != null) {
        b.remoteServiceName(remote);
      }
    }
    for (EventData e : span.getEvents()) {
      String value = e.getName();
      if (!e.getAttributes().isEmpty()) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.set(e.getName(), ZipkinModel.jsonValue(toMap(e.getAttributes())));
        value = ZipkinModel.json(o);
      }
      b.annotation(e.getEpochNanos() / 1_000L, value);
    }
    attributes.forEach((k, v) -> b.tag(k.getKey(), ZipkinModel.tagValue(v)));
    InstrumentationScopeInfo scope = span.getInstrumentationScopeInfo();
    if (!scope.getName().isEmpty()) {
      b.tag("otel.scope.name", scope.getName());
    }
    if (scope.getVersion() != null && !scope.getVersion().isEmpty()) {
      b.tag("otel.scope.version", scope.getVersion());
    }
    int droppedAttributes = span.getTotalAttributeCount() - attributes.size();
    if (droppedAttributes > 0) {
      b.tag("otel.dropped_attributes_count", Integer.toString(droppedAttributes));
    }
    int droppedEvents = span.getTotalRecordedEvents() - span.getEvents().size();
    if (droppedEvents > 0) {
      b.tag("otel.dropped_events_count", Integer.toString(droppedEvents));
    }
    StatusCode status = span.getStatus().getStatusCode();
    if (status == StatusCode.ERROR) {
      b.tag("otel.status_code", "ERROR");
      b.tag("error", span.getStatus().getDescription());
    } else if (status == StatusCode.OK) {
      b.tag("otel.status_code", "OK");
    }
    return b.build();
  }

  private static ZipkinKind kind(SpanKind kind) {
    switch (kind) {
      case SERVER:
        return ZipkinKind.SERVER;
      case CLIENT:
        return ZipkinKind.CLIENT;
      case PRODUCER:
        return ZipkinKind.PRODUCER;
      case CONSUMER:
        return ZipkinKind.CONSUMER;
      default:
        return null;
    }
  }

  private static String remoteServiceName(Attributes attributes) {
    for (String key : REMOTE_SERVICE_KEYS) {
      String v = attributes.get(AttributeKey.stringKey(key));
      if (v != null && !v.isEmpty()) {
        return v;
      }
    }
    return null;
  }

  private static Map<String, Object> toMap(Attributes attributes) {
    Map<String, Object> out = new LinkedHashMap<>();
    attributes.forEach((k, v) -> out.put(k.getKey(), v));
    return out;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (shutdown.get()) {
      return CompletableResultCode.ofFailure();
    }
    CompletableResultCode result = new CompletableResultCode();
    List<ZipkinSpan> batch;
    try {
      batch = new ArrayList<>(spans.size());
      for (SpanData s : spans) {
        batch.add(toZipkinSpan(s));
      }
    } catch (RuntimeException e) {
      notifyError(e);
      return CompletableResultCode.ofExceptionalFailure(e);
    }
    pending.add(result);
    reporter
        .reportAsync(batch)
        .whenComplete(
            (v, err) -> {
              pending.remove(result);
              if (err == null) {
                result.succeed();
              } else {
                Throwable cause =
                    codes.grow.packages.zipkin.core.HttpTransport.asSdkException(
                        err, "zipkin.export");
                notifyError(cause);
                result.failExceptionally(cause);
              }
            });
    return result;
  }

  private void notifyError(Throwable error) {
    if (onError != null) {
      try {
        onError.accept(error);
      } catch (RuntimeException ignored) {
        // hooks never break exporting
      }
    }
  }

  /**
   * Completes when every export in flight finished.
   *
   * @return the result
   */
  @Override
  public CompletableResultCode flush() {
    List<CompletableResultCode> inflight = new ArrayList<>(pending);
    if (inflight.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    CompletableResultCode done = new CompletableResultCode();
    CompletableResultCode.ofAll(inflight).whenComplete(done::succeed);
    return done;
  }

  /**
   * Waits for in-flight exports, then closes an owned reporter. Idempotent; later exports fail.
   *
   * @return the result
   */
  @Override
  public CompletableResultCode shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return CompletableResultCode.ofSuccess();
    }
    CompletableResultCode done = new CompletableResultCode();
    flush()
        .whenComplete(
            () -> {
              if (owned) {
                reporter.close();
              }
              done.succeed();
            });
    return done;
  }

  @Override
  public String toString() {
    return "ZipkinSpanExporter{" + reporter + "}";
  }

  /** Builder of a {@link ZipkinSpanExporter}. */
  public static final class Builder {
    private ZipkinReporter reporter;
    private ZipkinConfig config;
    private Compression compression = Compression.GZIP;
    private Integer maxBatchSize;
    private Consumer<Throwable> onError;

    private Builder() {}

    /**
     * Sends through this reporter (not closed by {@link ZipkinSpanExporter#shutdown()}); the other
     * connection options are then ignored.
     *
     * @param reporter reporter
     * @return this
     */
    public Builder reporter(ZipkinReporter reporter) {
      this.reporter = reporter;
      return this;
    }

    /**
     * Connection settings of an owned reporter (default {@link ZipkinConfig#fromEnv()}).
     *
     * @param config configuration
     * @return this
     */
    public Builder config(ZipkinConfig config) {
      this.config = config;
      return this;
    }

    /**
     * Request body compression of an owned reporter (default gzip).
     *
     * @param compression compression
     * @return this
     */
    public Builder compression(Compression compression) {
      this.compression = compression;
      return this;
    }

    /**
     * Spans per POST of an owned reporter (default 512).
     *
     * @param maxBatchSize positive size
     * @return this
     */
    public Builder maxBatchSize(int maxBatchSize) {
      this.maxBatchSize = maxBatchSize;
      return this;
    }

    /**
     * Called with every failed export (the batch is dropped). Never throws into the exporter.
     *
     * @param onError callback
     * @return this
     */
    public Builder onError(Consumer<Throwable> onError) {
      this.onError = onError;
      return this;
    }

    /**
     * Builds the exporter.
     *
     * @return exporter
     */
    public ZipkinSpanExporter build() {
      return new ZipkinSpanExporter(this);
    }
  }
}
