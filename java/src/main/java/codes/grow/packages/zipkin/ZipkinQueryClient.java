package codes.grow.packages.zipkin;

import codes.grow.packages.zipkin.core.HttpTransport;
import codes.grow.packages.zipkin.core.Redaction;
import codes.grow.packages.zipkin.core.TransportRequest;
import codes.grow.packages.zipkin.core.TransportResponse;
import codes.grow.packages.zipkin.core.ValidationException;
import codes.grow.packages.zipkin.tracing.query.DependencyLink;
import codes.grow.packages.zipkin.tracing.query.Trace;
import codes.grow.packages.zipkin.tracing.query.TraceQueryClient;
import codes.grow.packages.zipkin.tracing.query.TraceSearchQuery;
import codes.grow.packages.zipkin.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Reads traces back from Zipkin (port 9411): the native v2 query API ({@link #getTraceSpans},
 * {@link #getTraces}, {@link #getTraceMany}, {@link #listServices}, {@link #listSpans}, {@link
 * #listRemoteServices}, {@link #getDependencies(Instant, Duration)}, {@link #autocompleteKeys},
 * {@link #autocompleteValues}) and the SPEC section 5 {@link TraceQueryClient} ({@link #getTrace},
 * {@link #searchTraces}, {@link #listOperations}, {@link #waitForTrace}) on top of it. Every call
 * is a GET (retried on 5xx, 429 and network errors) and has an {@code …Async} variant.
 *
 * <p>Get one from {@link ZipkinClient#query()} (shares the client's connections) or create one with
 * {@link #create(ZipkinConfig)} (owns its connections; close it).
 */
public final class ZipkinQueryClient implements TraceQueryClient, AutoCloseable {
  /** Default {@code limit} of {@link #searchTraces} (SPEC section 5). */
  public static final int DEFAULT_SEARCH_LIMIT = 20;

  /** Window of {@link #searchTraces} without a start: the last hour before the end. */
  public static final Duration DEFAULT_SEARCH_WINDOW = Duration.ofHours(1);

  /**
   * Most trace ids sent in one {@code /api/v2/traceMany} request: 100 × 33 characters keeps the
   * request line under the server's 4096-byte limit (more ids answer 414).
   */
  public static final int TRACE_MANY_CHUNK = 100;

  private static final String AND = " and ";

  private final HttpTransport transport;
  private final boolean owned;
  private final Clock clock;
  private final AtomicBoolean closed = new AtomicBoolean();

  ZipkinQueryClient(HttpTransport transport, boolean owned, Clock clock) {
    this.transport = transport;
    this.owned = owned;
    this.clock = clock;
  }

  /**
   * A standalone query client.
   *
   * @param config configuration
   * @return a client that owns its connections
   */
  public static ZipkinQueryClient create(ZipkinConfig config) {
    config.validate();
    return new ZipkinQueryClient(
        new HttpTransport(config.toTransportConfig()), true, Clock.systemUTC());
  }

  /**
   * A standalone query client from {@code GROW_ZIPKIN_*} / {@code ZIPKIN_URL}.
   *
   * @return a client that owns its connections
   */
  public static ZipkinQueryClient fromEnv() {
    return create(ZipkinConfig.fromEnv());
  }

  private CompletableFuture<TransportResponse> get(
      String path, String operation, Map<String, Object> query, boolean allow404) {
    TransportRequest.Builder b = TransportRequest.get(path).operation(operation);
    query.forEach(
        (k, v) -> {
          if (v != null) {
            b.query(k, v);
          }
        });
    return ZipkinHttp.send(transport, b, allow404);
  }

  private <T> CompletableFuture<T> getJson(
      String path, String operation, Map<String, Object> query, Function<JsonNode, T> decode) {
    return get(path, operation, query, false).thenApply(r -> decode.apply(r.jsonNode()));
  }

  private static <T> T await(CompletableFuture<T> f, String operation) {
    return HttpTransport.await(f, operation);
  }

  // -------------------------------------------------------------------------------------------
  // Traces

  /**
   * One trace in the native model: {@code GET /api/v2/trace/{traceId}}. The id is lower-cased;
   * anything but 16 or 32 hex characters raises {@link ValidationException} without a request.
   * Spans come back in storage order.
   *
   * @param traceId trace id
   * @return the spans, or empty when the trace does not exist (404)
   */
  public Optional<List<ZipkinSpan>> getTraceSpans(String traceId) {
    return await(getTraceSpansAsync(traceId), "zipkin.getTrace");
  }

  /**
   * Asynchronous {@link #getTraceSpans}.
   *
   * @param traceId trace id
   * @return future spans
   */
  public CompletableFuture<Optional<List<ZipkinSpan>>> getTraceSpansAsync(String traceId) {
    return ZipkinHttp.guard(
        () -> {
          String id = ZipkinModel.zipkinTraceId(traceId, "zipkin.getTrace");
          return get("/api/v2/trace/" + id, "zipkin.getTrace " + id, Map.of(), true)
              .thenApply(
                  r -> {
                    if (r.status() == 404) {
                      return Optional.empty();
                    }
                    List<ZipkinSpan> spans = ZipkinModel.spansFromBody(r.jsonNode());
                    return spans.isEmpty() ? Optional.empty() : Optional.of(spans);
                  });
        });
  }

  /**
   * One trace in the SPEC section 5 model (see {@link ZipkinModel#toSpan}).
   *
   * @param traceId trace id (16 or 32 hex characters)
   * @return the trace, or empty when it does not exist
   */
  @Override
  public Optional<Trace> getTrace(String traceId) {
    return await(getTraceAsync(traceId), "zipkin.getTrace");
  }

  @Override
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId) {
    return getTraceSpansAsync(traceId).thenApply(o -> o.flatMap(ZipkinModel::toTrace));
  }

  /**
   * Searches traces with the server defaults (last 24 h, 10 traces).
   *
   * @return traces, newest first
   */
  public List<List<ZipkinSpan>> getTraces() {
    return getTraces(ZipkinTraceQuery.builder().build());
  }

  /**
   * Searches traces: {@code GET /api/v2/traces}, newest first, one span list per trace. No match
   * returns an empty list. Invalid filters ({@code limit <= 0}, a maximum duration without or below
   * the minimum, negative durations, non-positive {@code endTs}/{@code lookback}) raise {@link
   * ValidationException} without a request.
   *
   * @param query filter
   * @return traces, newest first
   */
  public List<List<ZipkinSpan>> getTraces(ZipkinTraceQuery query) {
    return await(getTracesAsync(query), "zipkin.getTraces");
  }

  /**
   * Asynchronous {@link #getTraces(ZipkinTraceQuery)}.
   *
   * @param query filter
   * @return future traces
   */
  public CompletableFuture<List<List<ZipkinSpan>>> getTracesAsync(ZipkinTraceQuery query) {
    return ZipkinHttp.guard(() -> tracesAsync(query));
  }

  private CompletableFuture<List<List<ZipkinSpan>>> tracesAsync(ZipkinTraceQuery q) {
    String op = "zipkin.getTraces";
    Long min = q.minDurationMicros().isPresent() ? q.minDurationMicros().getAsLong() : null;
    Long max = q.maxDurationMicros().isPresent() ? q.maxDurationMicros().getAsLong() : null;
    if ((min != null && min < 0) || (max != null && max < 0)) {
      throw new ValidationException(op + ": durations must be non-negative");
    }
    if (max != null && min == null) {
      throw new ValidationException(op + ": maxDurationMicros needs minDurationMicros");
    }
    if (max != null && max < min) {
      throw new ValidationException(op + ": maxDurationMicros must not be below minDurationMicros");
    }
    if (q.limit().isPresent() && q.limit().getAsInt() <= 0) {
      throw new ValidationException(op + ": limit must be a positive integer");
    }
    Long lookback = null;
    if (q.lookback().isPresent()) {
      lookback = q.lookback().get().toMillis();
      if (lookback <= 0) {
        throw new ValidationException(op + ": lookback must be positive");
      }
    }
    Long endTs = q.endTs().isPresent() ? epochMs(q.endTs().get(), "endTs", op) : null;
    List<Term> terms = new ArrayList<>();
    q.tags().forEach((k, v) -> terms.add(new Term(k, v == null ? "" : v)));
    q.annotations().forEach(k -> terms.add(new Term(k, null)));
    for (Term t : terms) {
      if (t.key == null || t.key.isEmpty()) {
        throw new ValidationException(op + ": tag keys and annotations must not be empty");
      }
    }
    List<Term> local = new ArrayList<>();
    List<String> parts = new ArrayList<>();
    q.annotationQuery().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(parts::add);
    for (Term t : terms) {
      if (t.serverSide()) {
        parts.add(t.text());
      } else {
        local.add(t);
      }
    }
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("serviceName", blank(q.serviceName()));
    params.put("remoteServiceName", blank(q.remoteServiceName()));
    params.put("spanName", blank(q.spanName()));
    params.put("annotationQuery", parts.isEmpty() ? null : String.join(AND, parts));
    params.put("minDuration", min);
    params.put("maxDuration", max);
    params.put("endTs", endTs);
    params.put("lookback", lookback);
    params.put("limit", q.limit().isPresent() ? q.limit().getAsInt() : null);
    return getJson("/api/v2/traces", op, params, ZipkinModel::tracesFromBody)
        .thenApply(
            traces -> {
              if (local.isEmpty()) {
                return traces;
              }
              List<List<ZipkinSpan>> out = new ArrayList<>();
              for (List<ZipkinSpan> t : traces) {
                if (local.stream().allMatch(term -> term.matches(t))) {
                  out.add(t);
                }
              }
              return List.copyOf(out);
            });
  }

  private static String blank(Optional<String> v) {
    return v.filter(s -> !s.isBlank()).orElse(null);
  }

  private static long epochMs(Instant t, String what, String op) {
    long ms = t.toEpochMilli();
    if (ms <= 0) {
      throw new ValidationException(op + ": " + what + " must be a positive time");
    }
    return ms;
  }

  /** One annotation-query term: {@code key=value} or a bare key. */
  private static final class Term {
    final String key;
    final String value;

    Term(String key, String value) {
      this.key = key;
      this.value = value;
    }

    boolean serverSide() {
      return !key.contains(AND) && !key.contains("=") && (value == null || !value.contains(AND));
    }

    String text() {
      return value == null ? key : key + "=" + value;
    }

    boolean matches(List<ZipkinSpan> spans) {
      for (ZipkinSpan s : spans) {
        if (value != null) {
          if (value.equals(s.tags().get(key))) {
            return true;
          }
        } else if (s.tags().containsKey(key)
            || s.annotations().stream().anyMatch(a -> a.value().equals(key))) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Builds a Zipkin {@code annotationQuery} from exact tag matches and bare terms (joined with
   * {@code " and "}).
   *
   * @param tags exact tag matches
   * @param annotations bare terms
   * @return the query, or empty when there are no terms
   * @throws ValidationException for a term Zipkin cannot express (contains {@code " and "}, or a
   *     key with {@code =}) or an empty term
   */
  public static Optional<String> buildAnnotationQuery(
      Map<String, String> tags, Collection<String> annotations) {
    List<String> parts = new ArrayList<>();
    List<Term> terms = new ArrayList<>();
    tags.forEach((k, v) -> terms.add(new Term(k, v == null ? "" : v)));
    annotations.forEach(k -> terms.add(new Term(k, null)));
    for (Term t : terms) {
      if (t.key == null || t.key.isEmpty()) {
        throw new ValidationException("zipkin.annotationQuery: empty term");
      }
      if (!t.serverSide()) {
        throw new ValidationException(
            "zipkin.annotationQuery: a term must not contain \" and \" (or \"=\" in a key)");
      }
      parts.add(t.text());
    }
    return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(AND, parts));
  }

  /**
   * Several traces by id: {@code GET /api/v2/traceMany}. Ids are lower-cased and de-duplicated (a
   * 16-character id equals its zero-padded 32-character form); an empty list returns an empty list
   * without a request, a single id uses {@code GET /api/v2/trace/{id}}, more are fetched in chunks
   * of {@value #TRACE_MANY_CHUNK}. Missing traces are omitted; the result follows the request
   * order.
   *
   * @param traceIds trace ids
   * @return traces in request order
   */
  public List<List<ZipkinSpan>> getTraceMany(Collection<String> traceIds) {
    return await(getTraceManyAsync(traceIds), "zipkin.getTraceMany");
  }

  /**
   * Asynchronous {@link #getTraceMany}.
   *
   * @param traceIds trace ids
   * @return future traces
   */
  public CompletableFuture<List<List<ZipkinSpan>>> getTraceManyAsync(Collection<String> traceIds) {
    return ZipkinHttp.guard(
        () -> {
          String op = "zipkin.getTraceMany";
          Set<String> seen = new LinkedHashSet<>();
          List<String> ids = new ArrayList<>();
          for (String raw : traceIds) {
            String id = ZipkinModel.zipkinTraceId(raw, op);
            if (seen.add(pad(id))) {
              ids.add(id);
            }
          }
          if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
          }
          Map<String, List<ZipkinSpan>> byId = new HashMap<>();
          CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
          for (int i = 0; i < ids.size(); i += TRACE_MANY_CHUNK) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + TRACE_MANY_CHUNK));
            chain =
                chain
                    .thenCompose(v -> chunk(chunk, op))
                    .thenAccept(
                        traces -> {
                          for (List<ZipkinSpan> t : traces) {
                            byId.put(pad(t.get(0).traceId()), t);
                          }
                        });
          }
          return chain.thenApply(
              v -> {
                List<List<ZipkinSpan>> out = new ArrayList<>();
                for (String id : ids) {
                  List<ZipkinSpan> t = byId.get(pad(id));
                  if (t != null) {
                    out.add(t);
                  }
                }
                return List.copyOf(out);
              });
        });
  }

  private CompletableFuture<List<List<ZipkinSpan>>> chunk(List<String> chunk, String op) {
    if (chunk.size() == 1) {
      return getTraceSpansAsync(chunk.get(0)).thenApply(o -> o.map(List::of).orElse(List.of()));
    }
    return getJson(
        "/api/v2/traceMany",
        op,
        Map.of("traceIds", String.join(",", chunk)),
        ZipkinModel::tracesFromBody);
  }

  private static String pad(String id) {
    String lower = id.toLowerCase(Locale.ROOT);
    return "0".repeat(Math.max(0, 32 - lower.length())) + lower;
  }

  /**
   * SPEC section 5 search: {@code service} → {@code serviceName}, {@code operation} → {@code
   * spanName}, {@code tags} → exact tag terms (see {@link ZipkinTraceQuery.Builder#tag}), durations
   * in µs, {@code endTs} = {@code end} (default now), {@code lookback} = {@code end - start} (the
   * last hour without a start), {@code limit} default 20. Returns one summary per trace (root = the
   * span without a parent, else the earliest).
   *
   * @param query filter
   * @return summaries, newest first
   */
  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    return await(searchTracesAsync(query), "zipkin.searchTraces");
  }

  @Override
  public CompletableFuture<List<TraceSummary>> searchTracesAsync(TraceSearchQuery query) {
    return ZipkinHttp.guard(
        () -> {
          String op = "zipkin.searchTraces";
          Instant end = query.end().orElseGet(clock::instant);
          long endMs = epochMs(end, "end", op);
          Duration lookback = DEFAULT_SEARCH_WINDOW;
          if (query.start().isPresent()) {
            long startMs = epochMs(query.start().get(), "start", op);
            if (startMs > endMs) {
              throw new ValidationException(op + ": start must not be after end");
            }
            lookback = Duration.ofMillis(Math.max(1, endMs - startMs));
          }
          ZipkinTraceQuery.Builder b =
              ZipkinTraceQuery.builder()
                  .endTs(Instant.ofEpochMilli(endMs))
                  .lookback(lookback)
                  .limit(query.limit())
                  .tags(query.tags());
          query.service().ifPresent(b::serviceName);
          query.operation().ifPresent(b::spanName);
          query.minDurationMicros().ifPresent(b::minDurationMicros);
          query.maxDurationMicros().ifPresent(b::maxDurationMicros);
          return tracesAsync(b.build())
              .thenApply(
                  traces -> {
                    List<TraceSummary> out = new ArrayList<>();
                    for (List<ZipkinSpan> t : traces) {
                      ZipkinModel.summarize(t).ifPresent(out::add);
                    }
                    return List.copyOf(out);
                  });
        });
  }

  // -------------------------------------------------------------------------------------------
  // Names

  /**
   * Service names that reported spans: {@code GET /api/v2/services} (sorted, lower-case).
   *
   * @return names
   */
  @Override
  public List<String> listServices() {
    return await(listServicesAsync(), "zipkin.listServices");
  }

  @Override
  public CompletableFuture<List<String>> listServicesAsync() {
    return getJson("/api/v2/services", "zipkin.listServices", Map.of(), ZipkinQueryClient::sorted);
  }

  private static List<String> sorted(JsonNode body) {
    List<String> out = ZipkinModel.stringsFromBody(body);
    out.sort(null);
    return List.copyOf(out);
  }

  private static String required(String value, String what, String op) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(op + ": " + what + " is required");
    }
    return value;
  }

  /**
   * Span names of a service: {@code GET /api/v2/spans?serviceName=} (sorted; unknown service →
   * empty).
   *
   * @param serviceName service name (case-insensitive)
   * @return span names
   */
  public List<String> listSpans(String serviceName) {
    return await(listSpansAsync(serviceName), "zipkin.listSpans");
  }

  /**
   * Asynchronous {@link #listSpans}.
   *
   * @param serviceName service name
   * @return future names
   */
  public CompletableFuture<List<String>> listSpansAsync(String serviceName) {
    return ZipkinHttp.guard(
        () ->
            getJson(
                "/api/v2/spans",
                "zipkin.listSpans",
                Map.of("serviceName", required(serviceName, "serviceName", "zipkin.listSpans")),
                ZipkinQueryClient::sorted));
  }

  /**
   * SPEC section 5 alias of {@link #listSpans}.
   *
   * @param service service name
   * @return span names
   */
  @Override
  public List<String> listOperations(String service) {
    return listSpans(service);
  }

  @Override
  public CompletableFuture<List<String>> listOperationsAsync(String service) {
    return listSpansAsync(service);
  }

  /**
   * Remote services called by a service ({@code remoteEndpoint.serviceName} on its spans): {@code
   * GET /api/v2/remoteServices?serviceName=} (sorted).
   *
   * @param serviceName service name
   * @return remote service names
   */
  public List<String> listRemoteServices(String serviceName) {
    return await(listRemoteServicesAsync(serviceName), "zipkin.listRemoteServices");
  }

  /**
   * Asynchronous {@link #listRemoteServices}.
   *
   * @param serviceName service name
   * @return future names
   */
  public CompletableFuture<List<String>> listRemoteServicesAsync(String serviceName) {
    String op = "zipkin.listRemoteServices";
    return ZipkinHttp.guard(
        () ->
            getJson(
                "/api/v2/remoteServices",
                op,
                Map.of("serviceName", required(serviceName, "serviceName", op)),
                ZipkinQueryClient::sorted));
  }

  // -------------------------------------------------------------------------------------------
  // Dependencies

  /**
   * Service dependency edges of the last 24 h (server default) up to now.
   *
   * @return links with {@code errorCount}, sorted by parent and child
   */
  public List<ZipkinDependencyLink> getDependencies() {
    return getDependencies((Instant) null, (Duration) null);
  }

  /**
   * Service dependency edges: {@code GET /api/v2/dependencies?endTs=&lookback=}, sorted by parent
   * and child, {@code errorCount} defaulting to 0. In-memory storage computes them on read;
   * persistent backends need the zipkin-dependencies job, so an empty list is normal there.
   *
   * @param endTs end of the window, or {@code null} for now
   * @param lookback window length, or {@code null} for the server default (24 h)
   * @return links with {@code errorCount}
   * @throws ValidationException for a non-positive lookback or end
   */
  public List<ZipkinDependencyLink> getDependencies(Instant endTs, Duration lookback) {
    return await(getDependenciesAsync(endTs, lookback), "zipkin.getDependencies");
  }

  /**
   * Asynchronous {@link #getDependencies(Instant, Duration)}.
   *
   * @param endTs end of the window, or {@code null} for now
   * @param lookback window length, or {@code null} for the server default
   * @return future links
   */
  public CompletableFuture<List<ZipkinDependencyLink>> getDependenciesAsync(
      Instant endTs, Duration lookback) {
    return ZipkinHttp.guard(
        () -> {
          String op = "zipkin.getDependencies";
          long endMs = epochMs(endTs != null ? endTs : clock.instant(), "endTs", op);
          Long lb = null;
          if (lookback != null) {
            lb = lookback.toMillis();
            if (lb <= 0) {
              throw new ValidationException(op + ": lookback must be a positive duration");
            }
          }
          Map<String, Object> params = new java.util.LinkedHashMap<>();
          params.put("endTs", endMs);
          params.put("lookback", lb);
          return getJson("/api/v2/dependencies", op, params, ZipkinModel::dependenciesFromBody);
        });
  }

  /**
   * SPEC section 5 dependencies of a time range: {@code endTs} = {@code end} (default now), {@code
   * lookback} = {@code end - start} (server default 24 h without a start). The category model has
   * no {@code errorCount}; use {@link #getDependencies(Instant, Duration)} for it.
   *
   * @param start range start or {@code null}
   * @param end range end or {@code null}
   * @return links
   */
  @Override
  public List<DependencyLink> getDependencies(Instant start, Instant end) {
    return await(getDependenciesAsync(start, end), "zipkin.getDependencies");
  }

  @Override
  public CompletableFuture<List<DependencyLink>> getDependenciesAsync(Instant start, Instant end) {
    return ZipkinHttp.guard(
        () -> {
          Instant e = end != null ? end : clock.instant();
          Duration lookback = start == null ? null : Duration.between(start, e);
          return getDependenciesAsync(e, lookback)
              .thenApply(
                  links -> links.stream().map(ZipkinDependencyLink::toDependencyLink).toList());
        });
  }

  // -------------------------------------------------------------------------------------------
  // Autocomplete

  /**
   * Tag keys the server indexes for autocomplete: {@code GET /api/v2/autocompleteKeys} (the
   * server's {@code AUTOCOMPLETE_KEYS} in configured order, empty when unset).
   *
   * @return keys
   */
  public List<String> autocompleteKeys() {
    return await(autocompleteKeysAsync(), "zipkin.autocompleteKeys");
  }

  /**
   * Asynchronous {@link #autocompleteKeys}.
   *
   * @return future keys
   */
  public CompletableFuture<List<String>> autocompleteKeysAsync() {
    return getJson(
        "/api/v2/autocompleteKeys",
        "zipkin.autocompleteKeys",
        Map.of(),
        b -> List.copyOf(ZipkinModel.stringsFromBody(b)));
  }

  /**
   * Distinct values seen for an autocomplete key: {@code GET /api/v2/autocompleteValues?key=}
   * (sorted; empty for a key that is not in {@code AUTOCOMPLETE_KEYS}).
   *
   * @param key tag key
   * @return values
   */
  public List<String> autocompleteValues(String key) {
    return await(autocompleteValuesAsync(key), "zipkin.autocompleteValues");
  }

  /**
   * Asynchronous {@link #autocompleteValues}.
   *
   * @param key tag key
   * @return future values
   */
  public CompletableFuture<List<String>> autocompleteValuesAsync(String key) {
    String op = "zipkin.autocompleteValues";
    return ZipkinHttp.guard(
        () ->
            getJson(
                "/api/v2/autocompleteValues",
                op,
                Map.of("key", required(key, "key", op)),
                ZipkinQueryClient::sorted));
  }

  /** Releases connections when this client owns them (no-op for a client's). Idempotent. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && owned) {
      transport.close();
    }
  }

  @Override
  public String toString() {
    return "ZipkinQueryClient{" + Redaction.redactUrl(transport.config().baseUrl()) + "}";
  }
}
