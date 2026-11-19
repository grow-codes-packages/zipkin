// Owned by this repository.
package codes.grow.packages.zipkin.examples;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.ZipkinTracingHandle;
import codes.grow.packages.zipkin.tracing.Spans;
import codes.grow.packages.zipkin.tracing.TracingConfig;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenTelemetry tracing to Zipkin: the package's own Zipkin exporter (v2 JSON to {@code
 * /api/v2/spans}), W3C + B3 propagation, span helpers and log correlation; then the trace is read
 * back.
 */
public final class TracingExample {
  private TracingExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    try (var client = ZipkinClient.fromEnv()) {
      String traceId;
      try (ZipkinTracingHandle tracing =
          client.initTracing(TracingConfig.builder("example-checkout").setGlobal(false).build())) {
        traceId =
            Spans.withSpan(
                "checkout",
                () -> {
                  Spans.addEvent("cart.loaded", Map.of("items", 3L));
                  Map<String, String> headers = new HashMap<>();
                  Spans.inject(headers); // traceparent + b3 for the outgoing request
                  System.out.println("outgoing headers: " + headers.keySet());
                  System.out.println("log fields: " + Spans.traceFields());
                  return Spans.currentTraceId().orElseThrow();
                });
        System.out.println("exporting to " + tracing.endpoint());
      } // closing the handle flushes the last spans
      System.out.println(client.query().waitForTrace(traceId).spans());
    }
  }
}
