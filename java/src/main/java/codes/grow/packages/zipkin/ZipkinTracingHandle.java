package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.tracing.TracingConfig;
import codes.grow.packages.zipkin.tracing.TracingHandle;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * A running Zipkin tracing setup: the generic {@link TracingHandle} plus the exporter endpoint in
 * use. Shut it down (or close it) before the process exits so the last spans are sent.
 */
public final class ZipkinTracingHandle implements AutoCloseable {
  private final TracingHandle handle;
  private final String endpoint;

  ZipkinTracingHandle(TracingHandle handle, String endpoint) {
    this.handle = handle;
    this.endpoint = endpoint;
  }

  /**
   * The generic handle (for the helpers and the conformance runner).
   *
   * @return the handle
   */
  public TracingHandle handle() {
    return handle;
  }

  /**
   * The exporter endpoint in use ({@code …/api/v2/spans} for the native protocol, else the
   * OpenTelemetry Collector endpoint).
   *
   * @return the endpoint
   */
  public String endpoint() {
    return endpoint;
  }

  /**
   * The resolved tracing config.
   *
   * @return config
   */
  public TracingConfig config() {
    return handle.config();
  }

  /**
   * The OpenTelemetry SDK instance.
   *
   * @return SDK
   */
  public OpenTelemetrySdk openTelemetry() {
    return handle.openTelemetry();
  }

  /**
   * The tracer provider.
   *
   * @return provider
   */
  public SdkTracerProvider tracerProvider() {
    return handle.tracerProvider();
  }

  /**
   * The configured propagators.
   *
   * @return propagators
   */
  public ContextPropagators propagators() {
    return handle.propagators();
  }

  /**
   * A tracer.
   *
   * @param name instrumentation scope name
   * @return tracer
   */
  public Tracer tracer(String name) {
    return handle.tracer(name);
  }

  /**
   * A tracer with a version.
   *
   * @param name instrumentation scope name
   * @param version scope version
   * @return tracer
   */
  public Tracer tracer(String name, String version) {
    return handle.tracer(name, version);
  }

  /** Exports every finished span now (waits up to the export timeout). */
  public void forceFlush() {
    handle.forceFlush();
  }

  /**
   * Exports every finished span now.
   *
   * @param timeout maximum wait
   */
  public void forceFlush(Duration timeout) {
    handle.forceFlush(timeout);
  }

  /**
   * Asynchronous {@link #forceFlush(Duration)}.
   *
   * @param timeout maximum wait
   * @return future
   */
  public CompletableFuture<Void> forceFlushAsync(Duration timeout) {
    return handle.forceFlushAsync(timeout);
  }

  /** Flushes and stops the tracer provider. Idempotent. */
  public void shutdown() {
    handle.shutdown();
  }

  /**
   * Asynchronous {@link #shutdown()}.
   *
   * @return future
   */
  public CompletableFuture<Void> shutdownAsync() {
    return handle.shutdownAsync();
  }

  /**
   * Whether {@link #shutdown()} was called.
   *
   * @return true once shut down
   */
  public boolean isShutdown() {
    return handle.isShutdown();
  }

  @Override
  public void close() {
    shutdown();
  }

  @Override
  public String toString() {
    return "ZipkinTracingHandle{endpoint="
        + codes.grow.packages.zipkin.core.Redaction.redactUrl(endpoint)
        + ", protocol="
        + handle.config().protocol().wireValue()
        + "}";
  }
}
