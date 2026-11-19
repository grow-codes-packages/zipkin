# Trace queries

`ZipkinQueryClient` reads traces back from Zipkin's v2 query API (port 9411). It offers the
**native** functions (`getTraceSpans`, `getTraces`, `getTraceMany`, `listServices`, `listSpans`,
`listRemoteServices`, `getDependencies`, `autocompleteKeys`, `autocompleteValues`) and the
category interface **`TraceQueryClient`** on top of them (`getTrace`, `searchTraces`,
`listOperations`, `getDependencies`, `waitForTrace`) with the normalized model
`Trace { traceId, spans[] }` / `Span { traceId, spanId, parentSpanId, name, kind, serviceName,
startMicros, endMicros, durationMicros, status, attributes, events, links }`.

Every call is a GET, retried on 5xx, 429 and network errors. Zipkin answers errors as plain text;
the text is kept in the error message (redacted). Invalid filters fail with `VALIDATION` before
a request is made.

=== "TypeScript"

    ```ts
    import { ZipkinClient, ZipkinQueryClient } from "@grow-codes/zipkin";

    const query = ZipkinClient.fromEnv().query;      // shares the client's connections
    const standalone = ZipkinQueryClient.fromEnv();  // own connections: await standalone.close()
    ```

=== "Rust"

    ```rust
    let query = grow_zipkin::ZipkinClient::from_env()?.query(); // shares the transport
    let standalone = grow_zipkin::ZipkinQueryClient::from_env()?;
    ```

=== "Go"

    ```go
    client, err := zipkin.NewClientFromEnv()
    if err != nil {
        return err
    }
    q := client.Query() // *zipkin.QueryClient, implements zipkin.TraceQueryClient
    ```

=== "Python"

    ```python
    from grow_zipkin import AsyncZipkinClient, ZipkinClient, ZipkinQueryClient

    query = ZipkinClient.from_env().query          # ZipkinQueryClient
    aquery = AsyncZipkinClient.from_env().query    # AsyncZipkinQueryClient (same methods, awaitable)
    standalone = ZipkinQueryClient.from_env()      # own connections; close() it
    ```

=== "Java"

    ```java
    ZipkinQueryClient query = ZipkinClient.fromEnv().query(); // every method has an …Async variant
    ZipkinQueryClient standalone = ZipkinQueryClient.fromEnv();
    ```

## getTraceSpans / getTrace

`GET /api/v2/trace/{traceId}`. `getTraceSpans` returns the stored spans (native model);
`getTrace` the normalized `Trace` (64-bit ids left-padded to 32 characters, spans sorted, Zipkin
`kind` lower-cased, `tags` → `attributes`, annotations → events, the `error` tag → status
`error`). Both return `null` / `None` / `nil` / `Optional.empty()` when the trace does not exist
(404). The id is lower-cased; anything but 16 or 32 hex characters is a `VALIDATION` error.

=== "TypeScript"

    ```ts
    const spans = await query.getTraceSpans("5af7183fb1d4cf5f0a6665df1d0a9b8c"); // ZipkinSpan[] | null
    const trace = await query.getTrace("5af7183fb1d4cf5f0a6665df1d0a9b8c");      // Trace | null
    ```

=== "Rust"

    ```rust
    let spans = query.get_trace_spans(trace_id).await?; // Option<Vec<ZipkinSpan>>
    let trace = query.get_trace(trace_id).await?;       // Option<Trace>
    ```

=== "Go"

    ```go
    spans, err := q.GetTraceSpans(ctx, traceID) // nil when missing
    trace, err := q.GetTrace(ctx, traceID)      // *zipkin.Trace, nil when missing
    ```

=== "Python"

    ```python
    spans = query.get_trace_spans(trace_id)  # list[ZipkinSpan] | None
    trace = query.get_trace(trace_id)        # Trace | None
    ```

=== "Java"

    ```java
    Optional<List<ZipkinSpan>> spans = query.getTraceSpans(traceId);
    Optional<Trace> trace = query.getTrace(traceId);
    ```

## getTraces

`GET /api/v2/traces`: traces as stored, newest first, one span list per trace; no match → `[]`.
Filters: `serviceName`, `remoteServiceName`, `spanName`, `annotationQuery` (raw Zipkin syntax),
`tags` (exact `key=value`), `annotations` (bare terms: a tag key or an annotation value),
`minDuration` / `maxDuration` (µs; max needs min), `endTs` (default now), `lookback` (default the
server's 24 h) and `limit` (default the server's 10). `annotationQuery`, `tags` and `annotations`
are joined with ` and ` (Zipkin knows AND only). A term whose key or value contains ` and ` (or a
key with `=`) cannot be expressed in Zipkin's syntax: it is matched **client-side** after the query,
so fewer than `limit` traces may come back.

=== "TypeScript"

    ```ts
    const traces = await query.getTraces({
      serviceName: "frontend",
      spanName: "get /orders",
      tags: { "http.method": "GET" },
      annotations: ["error"],
      minDurationMicros: 100_000,
      lookbackMs: 15 * 60_000,
      limit: 5,
    });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::ZipkinTraceQuery;

    let traces = query
        .get_traces(
            &ZipkinTraceQuery::service("frontend")
                .span_name("get /orders")
                .tag("http.method", "GET")
                .annotation("error")
                .duration(Some(Duration::from_millis(100)), None)
                .window(SystemTime::now(), Duration::from_secs(900))
                .limit(5),
        )
        .await?;
    ```

=== "Go"

    ```go
    traces, err := q.GetTraces(ctx, zipkin.TracesQuery{
        ServiceName: "frontend",
        SpanName:    "get /orders",
        Tags:        map[string]string{"http.method": "GET"},
        Annotations: []string{"error"},
        MinDuration: 100 * time.Millisecond,
        Lookback:    15 * time.Minute,
        Limit:       5,
    })
    ```

=== "Python"

    ```python
    traces = query.get_traces(
        service_name="frontend",
        span_name="get /orders",
        tags={"http.method": "GET"},
        annotations=["error"],
        min_duration_micros=100_000,
        lookback_s=900,
        limit=5,
    )  # or query.get_traces(ZipkinTraceQuery(...))
    ```

=== "Java"

    ```java
    List<List<ZipkinSpan>> traces = query.getTraces(ZipkinTraceQuery.builder()
        .serviceName("frontend")
        .spanName("get /orders")
        .tag("http.method", "GET")
        .annotation("error")
        .minDurationMicros(100_000)
        .lookback(Duration.ofMinutes(15))
        .limit(5)
        .build());
    ```

## buildAnnotationQuery

Builds an `annotationQuery` string from tags and bare terms (`http.method=GET and error`); empty
when both are empty. A term Zipkin cannot express is a `VALIDATION` error. Tag order follows the
map (sorted in Go and Rust).

=== "TypeScript"

    ```ts
    buildAnnotationQuery({ "http.method": "GET" }, ["error"]); // "http.method=GET and error"
    ```

=== "Rust"

    ```rust
    let q = grow_zipkin::build_annotation_query(&tags, &["error".to_owned()])?; // Option<String>
    ```

=== "Go"

    ```go
    q, err := zipkin.BuildAnnotationQuery(map[string]string{"http.method": "GET"}, []string{"error"})
    ```

=== "Python"

    ```python
    q = build_annotation_query({"http.method": "GET"}, ["error"])  # str | None
    ```

=== "Java"

    ```java
    Optional<String> q = ZipkinQueryClient.buildAnnotationQuery(Map.of("http.method", "GET"), List.of("error"));
    ```

## getTraceMany

`GET /api/v2/traceMany?traceIds=…`. Ids are lower-cased and de-duplicated (a 16-character id
equals its zero-padded 32-character form). An empty list returns `[]` without a request, one id
uses `getTraceSpans`, more go in chunks of **100** ids (longer URLs get HTTP 414 from Zipkin).
Missing traces are omitted; the result follows the request order.

=== "TypeScript"

    ```ts
    const traces = await query.getTraceMany([idA, idB, idA]); // two traces at most, in order
    ```

=== "Rust"

    ```rust
    let traces = query.get_trace_many(&[id_a, id_b]).await?;
    ```

=== "Go"

    ```go
    traces, err := q.GetTraceMany(ctx, []string{idA, idB})
    ```

=== "Python"

    ```python
    traces = query.get_trace_many([id_a, id_b])
    ```

=== "Java"

    ```java
    List<List<ZipkinSpan>> traces = query.getTraceMany(List.of(idA, idB));
    ```

## searchTraces

The category search, mapped to `getTraces`: `service` → `serviceName`, `operation` → `spanName`,
`tags` → exact tag terms, durations in µs, `end` (default now) → `endTs`, `end - start` →
`lookback` — **the last hour when `start` is not set** (the same default in every tracing package
of Grow Codes), `limit` default 20. Returns one `TraceSummary { traceId, rootServiceName, rootName,
startMicros, durationMicros, spanCount }` per trace (root = the span without a parent, else the
earliest).

=== "TypeScript"

    ```ts
    const summaries = await query.searchTraces({ service: "frontend", tags: { "http.method": "GET" }, limit: 10 });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::tracing::TraceSearchQuery;

    let summaries = query
        .search_traces(&TraceSearchQuery::service("frontend").tag("http.method", "GET").limit(10))
        .await?;
    ```

=== "Go"

    ```go
    summaries, err := q.SearchTraces(ctx, zipkin.TraceSearchQuery{
        Service: "frontend", Tags: map[string]string{"http.method": "GET"}, Limit: 10,
    })
    ```

=== "Python"

    ```python
    from grow_zipkin import TraceSearchQuery

    summaries = query.search_traces(TraceSearchQuery(service="frontend", tags={"http.method": "GET"}, limit=10))
    ```

=== "Java"

    ```java
    List<TraceSummary> summaries = query.searchTraces(TraceSearchQuery.builder()
        .service("frontend").tag("http.method", "GET").limit(10).build());
    ```

## listServices / listSpans / listOperations / listRemoteServices

`GET /api/v2/services`, `/api/v2/spans?serviceName=` and `/api/v2/remoteServices?serviceName=`,
sorted. Names are lower-case (Zipkin lower-cases them on ingest); the service name is matched
case-insensitively. An unknown service returns `[]`; an empty service name is a `VALIDATION`
error. `listOperations` is the category alias of `listSpans`.

=== "TypeScript"

    ```ts
    const services = await query.listServices();
    const spanNames = await query.listSpans("frontend");    // = listOperations("frontend")
    const remotes = await query.listRemoteServices("frontend");
    ```

=== "Rust"

    ```rust
    let services = query.list_services().await?;
    let span_names = query.list_spans("frontend").await?;
    let remotes = query.list_remote_services("frontend").await?;
    ```

=== "Go"

    ```go
    services, err := q.ListServices(ctx)
    spanNames, err := q.ListSpans(ctx, "frontend") // = q.ListOperations
    remotes, err := q.ListRemoteServices(ctx, "frontend")
    ```

=== "Python"

    ```python
    services = query.list_services()
    span_names = query.list_spans("frontend")
    remotes = query.list_remote_services("frontend")
    ```

=== "Java"

    ```java
    List<String> services = query.listServices();
    List<String> spanNames = query.listSpans("frontend");
    List<String> remotes = query.listRemoteServices("frontend");
    ```

## getDependencies

`GET /api/v2/dependencies?endTs=&lookback=`: service dependency edges sorted by parent and child.
The Zipkin link keeps `errorCount` (0 when Zipkin omits it). The window is either the category
range (`start` / `end`) or Zipkin's native `endTs` + `lookback`; without a start or lookback the
server default (24 h) applies. In-memory storage computes edges on read; persistent backends need
the [zipkin-dependencies](https://github.com/openzipkin/zipkin-dependencies) job, so `[]` is normal
there. Edges come from CLIENT → SERVER span pairs and from CLIENT/PRODUCER spans with a remote
service name.

=== "TypeScript"

    ```ts
    const links = await query.getDependencies({ lookbackMs: 3_600_000 }); // ZipkinDependencyLink[]
    const range = await query.getDependencies({ start: new Date(Date.now() - 3_600_000) });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::ZipkinDependencyOptions;

    let links = query
        .get_dependencies(ZipkinDependencyOptions::window(SystemTime::now(), Duration::from_secs(3600)))
        .await?;
    ```

=== "Go"

    ```go
    links, err := q.GetDependenciesAt(ctx, time.Now(), time.Hour)                                // with ErrorCount
    spec, err := q.GetDependencies(ctx, zipkin.DependencyOptions{Start: time.Now().Add(-time.Hour)}) // category model
    ```

=== "Python"

    ```python
    links = query.get_dependencies(lookback_s=3600)  # list[ZipkinDependencyLink] (error_count)
    ```

=== "Java"

    ```java
    List<ZipkinDependencyLink> links = query.getDependencies(Instant.now(), Duration.ofHours(1));
    List<DependencyLink> range = query.getDependencies(Instant.now().minusSeconds(3600), Instant.now());
    ```

## autocompleteKeys / autocompleteValues

`GET /api/v2/autocompleteKeys` returns the server's `AUTOCOMPLETE_KEYS` in configured order (`[]`
when unset — the Grow stack template sets none). `GET /api/v2/autocompleteValues?key=` returns
the sorted distinct values of one key (`[]` for a key outside the allowlist).

=== "TypeScript"

    ```ts
    const keys = await query.autocompleteKeys();
    const methods = await query.autocompleteValues("http.method");
    ```

=== "Rust"

    ```rust
    let keys = query.autocomplete_keys().await?;
    let methods = query.autocomplete_values("http.method").await?;
    ```

=== "Go"

    ```go
    keys, err := q.AutocompleteKeys(ctx)
    methods, err := q.AutocompleteValues(ctx, "http.method")
    ```

=== "Python"

    ```python
    keys = query.autocomplete_keys()
    methods = query.autocomplete_values("http.method")
    ```

=== "Java"

    ```java
    List<String> keys = query.autocompleteKeys();
    List<String> methods = query.autocompleteValues("http.method");
    ```

## waitForTrace

Polls `getTrace` until the trace has at least `minSpans` spans (default 1) or the timeout
(default 30 s) passes (`TIMEOUT`). A test helper; with in-memory storage traces are queryable
right after the report.

=== "TypeScript"

    ```ts
    const trace = await query.waitForTrace(traceId, { minSpans: 2, timeoutMs: 15_000 });
    ```

=== "Rust"

    ```rust
    use grow_zipkin::tracing::WaitForTraceOptions;

    let trace = query
        .wait_for_trace(&trace_id, WaitForTraceOptions::default().min_spans(2).timeout(Duration::from_secs(15)))
        .await?;
    ```

=== "Go"

    ```go
    trace, err := q.WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{MinSpans: 2, Timeout: 15 * time.Second})
    ```

=== "Python"

    ```python
    trace = query.wait_for_trace(trace_id, min_spans=2, timeout_s=15)
    ```

=== "Java"

    ```java
    Trace trace = query.waitForTrace(traceId, new WaitForTraceOptions(Duration.ofSeconds(15), null, 2));
    ```
