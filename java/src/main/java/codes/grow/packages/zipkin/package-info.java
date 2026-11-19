/**
 * Grow Codes SDK for Zipkin.
 *
 * <p>Start with {@link codes.grow.packages.zipkin.ZipkinClient#fromEnv()}. The client gives access
 * to the query API ({@link codes.grow.packages.zipkin.ZipkinQueryClient}), the direct span reporter
 * ({@link codes.grow.packages.zipkin.ZipkinReporter}) and OpenTelemetry tracing through the
 * package's own Zipkin exporter ({@link codes.grow.packages.zipkin.ZipkinTracing}, {@link
 * codes.grow.packages.zipkin.ZipkinSpanExporter}).
 *
 * <p>Shared building blocks live in {@code core} (errors, transport, redaction); the tracing
 * category API in {@code tracing}; in-memory fakes and the conformance runner in {@code testing}.
 */
package codes.grow.packages.zipkin;
