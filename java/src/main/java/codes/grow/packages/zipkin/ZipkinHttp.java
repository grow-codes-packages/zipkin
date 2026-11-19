package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.ErrorCode;
import codes.grow.packages.zipkin.core.Hooks;
import codes.grow.packages.zipkin.core.HttpTransport;
import codes.grow.packages.zipkin.core.Redaction;
import codes.grow.packages.zipkin.core.TransportRequest;
import codes.grow.packages.zipkin.core.TransportResponse;
import codes.grow.packages.zipkin.core.ZipkinException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Sends requests to Zipkin and maps its error responses.
 *
 * <p>Zipkin answers every 4xx with a plain-text body and the content type {@code text/*}, which the
 * generic transport does not read. These statuses are accepted here and mapped with the vendor text
 * (the same message format, error classes and {@code onError} hook as the transport).
 */
final class ZipkinHttp {
  /** 4xx statuses mapped here (401/403/408/429 come from gateways and stay with the transport). */
  static final int[] VENDOR_STATUSES = {400, 404, 405, 409, 413, 414, 415, 422};

  private ZipkinHttp() {}

  /**
   * Sends a request whose builder already carries path, query and operation.
   *
   * @param transport transport
   * @param request request builder
   * @param allow404 return a 404 response instead of raising {@code NotFoundException}
   * @return the response future (2xx, or 404 when allowed)
   */
  static CompletableFuture<TransportResponse> send(
      HttpTransport transport, TransportRequest.Builder request, boolean allow404) {
    TransportRequest req = request.acceptStatus(VENDOR_STATUSES).build();
    return transport
        .sendAsync(req)
        .thenApply(
            r -> {
              int status = r.status();
              if (status < 300 || (allow404 && status == 404)) {
                return r;
              }
              throw error(transport, req, r);
            });
  }

  private static ZipkinException error(
      HttpTransport transport, TransportRequest req, TransportResponse r) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    r.headers()
        .forEach(
            (k, v) -> {
              if (!k.equalsIgnoreCase("content-type")) {
                headers.put(k, v);
              }
            });
    headers.put("content-type", List.of("text/plain"));
    String message =
        HttpTransport.errorMessage(
            req.operation(), r.status(), headers, r.body(), transport.config().knownSecrets());
    ZipkinException e =
        ZipkinException.of(
            ErrorCode.forStatus(r.status()),
            message,
            r.status(),
            r.requestId().orElse(null),
            null,
            null);
    try {
      String url = Redaction.redactUrl(transport.resolve(req.path(), req.query()));
      transport
          .config()
          .hooks()
          .onError(new Hooks.ErrorInfo(req.method(), url, e, r.attempts(), false));
    } catch (RuntimeException ignored) {
      // hooks never break a call
    }
    return e;
  }

  /**
   * Runs {@code body}; an exception it throws (local validation) becomes a failed future.
   *
   * @param <T> result type
   * @param body future supplier
   * @return the future
   */
  static <T> CompletableFuture<T> guard(Supplier<CompletableFuture<T>> body) {
    try {
      return body.get();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
