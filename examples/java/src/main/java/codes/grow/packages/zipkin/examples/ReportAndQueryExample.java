// Owned by this repository.
package codes.grow.packages.zipkin.examples;

import codes.grow.packages.zipkin.ZipkinClient;
import codes.grow.packages.zipkin.ZipkinDependencyLink;
import codes.grow.packages.zipkin.ZipkinKind;
import codes.grow.packages.zipkin.ZipkinQueryClient;
import codes.grow.packages.zipkin.ZipkinSpan;
import codes.grow.packages.zipkin.ZipkinTraceQuery;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceSearchQuery;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Reports spans without OpenTelemetry ({@code ZipkinReporter}) and reads them back with every
 * query function of {@code ZipkinQueryClient}.
 */
public final class ReportAndQueryExample {
  private ReportAndQueryExample() {}

  private static String hex(int bytes) {
    byte[] b = new byte[bytes];
    new SecureRandom().nextBytes(b);
    b[0] |= 0x10;
    return HexFormat.of().formatHex(b);
  }

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    String traceId = hex(16);
    String rootId = hex(8);
    String callId = hex(8);
    Instant now = Instant.now();
    try (var client = ZipkinClient.fromEnv()) {
      // frontend SERVER → frontend CLIENT → backend (remote endpoint)
      client
          .reporter()
          .report(
              List.of(
                  ZipkinSpan.builder(traceId, rootId)
                      .kind(ZipkinKind.SERVER)
                      .name("GET /orders")
                      .localServiceName("example-frontend")
                      .timestamp(now)
                      .duration(Duration.ofMillis(120))
                      .annotation(now.toEpochMilli() * 1000 + 1000, "cache.miss")
                      .tag("http.method", "GET")
                      .tag("http.status_code", 200)
                      .build(),
                  ZipkinSpan.builder(traceId, callId)
                      .parentId(rootId)
                      .kind("client")
                      .name("select orders")
                      .localServiceName("example-frontend")
                      .remoteServiceName("example-db")
                      .timestamp(now.plusMillis(5))
                      .duration(Duration.ofMillis(80))
                      .build()));

      ZipkinQueryClient query = client.query();
      Trace trace = query.waitForTrace(traceId); // the SPEC model (normalized spans)
      System.out.println("trace " + trace.traceId() + " has " + trace.spans().size() + " spans");
      System.out.println("raw spans: " + query.getTraceSpans(traceId).orElseThrow().size());
      System.out.println(
          "getTraces: "
              + query
                  .getTraces(
                      ZipkinTraceQuery.builder()
                          .serviceName("example-frontend")
                          .annotationQuery("http.method=GET and cache.miss")
                          .lookback(Duration.ofMinutes(15))
                          .limit(5)
                          .build())
                  .size());
      System.out.println("getTraceMany: " + query.getTraceMany(List.of(traceId, hex(16))).size());
      System.out.println(
          "searchTraces: "
              + query.searchTraces(TraceSearchQuery.forService("example-frontend")));
      System.out.println("services: " + query.listServices());
      System.out.println("spans: " + query.listSpans("example-frontend"));
      System.out.println("remote services: " + query.listRemoteServices("example-frontend"));
      for (ZipkinDependencyLink link : query.getDependencies(null, Duration.ofHours(1))) {
        System.out.println(
            "dependency " + link.parent() + " -> " + link.child() + " calls=" + link.callCount());
      }
      List<String> keys = query.autocompleteKeys(); // the server's AUTOCOMPLETE_KEYS
      System.out.println("autocomplete keys: " + keys);
      for (String key : keys) {
        System.out.println("  " + key + ": " + query.autocompleteValues(key));
      }
    }
  }
}
