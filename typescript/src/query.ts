// Service-owned: ZipkinQueryClient — the Zipkin v2 query API and the SPEC §5 TraceQueryClient.

import { HttpTransport, type QueryValue, ValidationError } from "./core/index.js";
import { ZipkinConfig, type ZipkinConfigOptions } from "./config.js";
import {
  dependenciesFromBody,
  stringsFromBody,
  summarizeZipkinTrace,
  zipkinSpansFromBody,
  zipkinTraceId,
  zipkinTraceToTrace,
  zipkinTracesFromBody,
  type ZipkinDependencyLink,
  type ZipkinSpan,
} from "./model.js";
import {
  createZipkinTransport,
  isTransportSource,
  type TransportSource,
  type ZipkinInternals,
} from "./reporter.js";
import {
  BaseTraceQueryClient,
  type DependencyOptions,
  type Trace,
  type TraceSearchQuery,
  type TraceSummary,
} from "./tracing/index.js";

/** Per-call options of the query client. */
export interface QueryCallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/**
 * Filter of {@link ZipkinQueryClient.getTraces} (`GET /api/v2/traces`). Every field is
 * optional; unset fields use the server defaults (`endTs` = now, `lookback` = 24 h, `limit` = 10).
 * `annotationQuery`, `tags` and `annotations` are combined with ` and ` (Zipkin supports AND
 * only).
 */
export interface ZipkinTraceQuery extends QueryCallOptions {
  /** Local service name (matched case-insensitively). */
  serviceName?: string;
  /** Remote service name (`remoteEndpoint.serviceName`) seen on `serviceName`'s spans. */
  remoteServiceName?: string;
  /** Span name. */
  spanName?: string;
  /** Raw Zipkin annotation query, e.g. `http.method=GET and error`. */
  annotationQuery?: string;
  /**
   * Exact tag matches (`key=value`). A key or value that contains ` and ` (or a key that
   * contains `=`) cannot be expressed in Zipkin's syntax: that term is matched client-side after
   * the query (so fewer than `limit` traces may come back).
   */
  tags?: Record<string, string>;
  /** Bare terms: a tag key or an annotation value (`error`, `cache.miss`). */
  annotations?: string[];
  /** Minimum span duration in microseconds. */
  minDurationMicros?: number;
  /** Maximum span duration in microseconds (needs `minDurationMicros`). */
  maxDurationMicros?: number;
  /** End of the window: a `Date` or epoch milliseconds. Default: server now. */
  endTs?: Date | number;
  /** Window length before `endTs` in ms. Default: server `QUERY_LOOKBACK` (24 h). */
  lookbackMs?: number;
  /** Maximum number of traces. Default: server default (10). */
  limit?: number;
}

/**
 * Window of {@link ZipkinQueryClient.getDependencies}: either the category window
 * (`start`/`end`) or Zipkin's native `endTs` + `lookbackMs` (these win when given).
 */
export interface ZipkinDependencyOptions extends DependencyOptions, QueryCallOptions {
  /** End of the window (`Date` or epoch ms). Default: `end`, else now. */
  endTs?: Date | number;
  /** Window length in ms. Default: `endTs - start`, else the server default (24 h). */
  lookbackMs?: number;
}

/** Default `limit` of {@link ZipkinQueryClient.searchTraces} (SPEC §5). */
export const DEFAULT_SEARCH_LIMIT = 20;
/** Window of {@link ZipkinQueryClient.searchTraces} when no `start` is given: the last hour. */
export const DEFAULT_SEARCH_WINDOW_MS = 3_600_000;
/**
 * Most trace ids sent in one `/api/v2/traceMany` request: 100 × 33 characters keeps the request
 * line under Armeria's default HTTP/1 limit of 4096 bytes.
 */
export const TRACE_MANY_CHUNK = 100;

const AND = " and ";

interface Term {
  key: string;
  value?: string;
}

function epochMs(value: Date | number, what: string, operation: string): number {
  const ms = value instanceof Date ? value.getTime() : value;
  if (!Number.isFinite(ms) || ms <= 0)
    throw new ValidationError(`${operation}: ${what} must be a positive time`);
  return Math.round(ms);
}

function nonEmpty(value: string, what: string, operation: string): string {
  if (typeof value !== "string" || value.trim() === "")
    throw new ValidationError(`${operation}: ${what} is required`);
  return value;
}

function serverSide(term: Term): boolean {
  return !term.key.includes(AND) && !term.key.includes("=") && !(term.value ?? "").includes(AND);
}

function termText(term: Term): string {
  return term.value === undefined ? term.key : `${term.key}=${term.value}`;
}

function traceMatches(spans: readonly ZipkinSpan[], term: Term): boolean {
  return spans.some((s) => {
    const tags = s.tags ?? {};
    if (term.value !== undefined) return tags[term.key] === term.value;
    return (
      Object.prototype.hasOwnProperty.call(tags, term.key) ||
      (s.annotations ?? []).some((a) => a.value === term.key)
    );
  });
}

/**
 * Builds a Zipkin `annotationQuery` from exact tag matches and bare terms (joined with ` and `).
 * Throws `ValidationError` for a term Zipkin cannot express (contains ` and `, or a key with `=`).
 */
export function buildAnnotationQuery(
  tags: Record<string, string> = {},
  annotations: readonly string[] = [],
): string | undefined {
  const terms: Term[] = [
    ...Object.entries(tags).map(([key, value]) => ({ key, value })),
    ...annotations.map((key) => ({ key })),
  ];
  for (const term of terms) {
    if (term.key === "") throw new ValidationError("zipkin.annotationQuery: empty term");
    if (!serverSide(term))
      throw new ValidationError(
        'zipkin.annotationQuery: a term must not contain " and " (or "=" in a key)',
      );
  }
  return terms.length === 0 ? undefined : terms.map(termText).join(AND);
}

/**
 * Reads traces back from Zipkin (port 9411): the native v2 query API (`getTraceSpans`,
 * `getTraces`, `getTraceMany`, `listServices`, `listSpans`, `listRemoteServices`,
 * `getDependencies`, `autocompleteKeys`, `autocompleteValues`) and the SPEC §5
 * `TraceQueryClient` (`getTrace`, `searchTraces`, `listOperations`, `waitForTrace`) on top of it.
 * Every call is a GET (retried on 5xx, 429 and network errors).
 *
 * Get one from {@link ZipkinClient.query} (shares the client's connections) or construct it from
 * a config (owns its connections; call {@link close}).
 */
export class ZipkinQueryClient extends BaseTraceQueryClient {
  readonly #transport: HttpTransport;
  readonly #owned: boolean;
  readonly #now: () => number;

  /**
   * @param source a {@link ZipkinClient} (shared transport) or config options (own transport).
   * @param internals test seams.
   */
  constructor(source: TransportSource | ZipkinConfigOptions = {}, internals: ZipkinInternals = {}) {
    super();
    this.#now = internals.now ?? Date.now;
    if (isTransportSource(source)) {
      this.#transport = source.transport;
      this.#owned = false;
    } else {
      this.#transport = createZipkinTransport(ZipkinConfig.from(source), internals);
      this.#owned = true;
    }
  }

  /** Builds a standalone query client from `GROW_ZIPKIN_*` / `ZIPKIN_URL`. */
  static fromEnv(overrides: ZipkinConfigOptions = {}): ZipkinQueryClient {
    return new ZipkinQueryClient(ZipkinConfig.fromEnv(overrides));
  }

  async #get(
    path: string,
    operation: string,
    query: Record<string, QueryValue>,
    signal: AbortSignal | undefined,
    allowStatus?: readonly number[],
  ): Promise<{ status: number; data: unknown }> {
    const res = await this.#transport.get(path, {
      operation,
      query,
      ...(allowStatus === undefined ? {} : { allowStatus }),
      ...(signal === undefined ? {} : { signal }),
    });
    return { status: res.status, data: res.data };
  }

  /**
   * One trace in the native model: `GET /api/v2/trace/{traceId}`. Returns `null` when the trace
   * does not exist (404). The id is lower-cased; anything but 16 or 32 hex characters throws
   * `ValidationError` without a request. Spans come back in storage order.
   */
  async getTraceSpans(traceId: string, opts: QueryCallOptions = {}): Promise<ZipkinSpan[] | null> {
    const id = zipkinTraceId(traceId, "zipkin.getTrace");
    const res = await this.#get(
      `/api/v2/trace/${id}`,
      `zipkin.getTrace ${id}`,
      {},
      opts.signal,
      [404],
    );
    if (res.status === 404) return null;
    const spans = zipkinSpansFromBody(res.data);
    return spans.length === 0 ? null : spans;
  }

  /**
   * One trace in the SPEC §5 model (see {@link zipkinSpanToSpan}); `null` when it does not exist.
   */
  async getTrace(traceId: string, opts: QueryCallOptions = {}): Promise<Trace | null> {
    const spans = await this.getTraceSpans(traceId, opts);
    return spans === null ? null : zipkinTraceToTrace(spans);
  }

  /**
   * Searches traces: `GET /api/v2/traces`, newest first, one `ZipkinSpan[]` per trace. No match
   * returns `[]`. Invalid filters (`limit <= 0`, `maxDurationMicros` without or below
   * `minDurationMicros`, non-positive `endTs`/`lookbackMs`) throw `ValidationError` without a
   * request.
   */
  async getTraces(query: ZipkinTraceQuery = {}): Promise<ZipkinSpan[][]> {
    const operation = "zipkin.getTraces";
    const { min, max } = durations(query, operation);
    if (query.limit !== undefined && (!Number.isInteger(query.limit) || query.limit <= 0))
      throw new ValidationError(`${operation}: limit must be a positive integer`);
    if (
      query.lookbackMs !== undefined &&
      (!Number.isFinite(query.lookbackMs) || query.lookbackMs <= 0)
    )
      throw new ValidationError(`${operation}: lookbackMs must be positive`);
    const terms: Term[] = [
      ...Object.entries(query.tags ?? {}).map(([key, value]) => ({ key, value })),
      ...(query.annotations ?? []).map((key) => ({ key })),
    ];
    if (terms.some((t) => t.key === ""))
      throw new ValidationError(`${operation}: tag keys and annotations must not be empty`);
    const local = terms.filter((t) => !serverSide(t));
    const parts = [
      ...(query.annotationQuery === undefined || query.annotationQuery.trim() === ""
        ? []
        : [query.annotationQuery.trim()]),
      ...terms.filter(serverSide).map(termText),
    ];
    const res = await this.#get(
      "/api/v2/traces",
      operation,
      {
        serviceName: blankToUndefined(query.serviceName),
        remoteServiceName: blankToUndefined(query.remoteServiceName),
        spanName: blankToUndefined(query.spanName),
        annotationQuery: parts.length === 0 ? undefined : parts.join(AND),
        minDuration: min,
        maxDuration: max,
        endTs: query.endTs === undefined ? undefined : epochMs(query.endTs, "endTs", operation),
        lookback: query.lookbackMs === undefined ? undefined : Math.round(query.lookbackMs),
        limit: query.limit,
      },
      query.signal,
    );
    const traces = zipkinTracesFromBody(res.data);
    return local.length === 0
      ? traces
      : traces.filter((t) => local.every((term) => traceMatches(t, term)));
  }

  /**
   * Several traces by id: `GET /api/v2/traceMany`. Ids are lower-cased and de-duplicated (a
   * 16-character id equals its zero-padded 32-character form); an empty list returns `[]`
   * without a request, a single id uses `GET /api/v2/trace/{id}`, more are fetched in chunks of
   * {@link TRACE_MANY_CHUNK}. Missing traces are omitted; the result follows the request order.
   */
  async getTraceMany(
    traceIds: readonly string[],
    opts: QueryCallOptions = {},
  ): Promise<ZipkinSpan[][]> {
    const operation = "zipkin.getTraceMany";
    const ids: string[] = [];
    const seen = new Set<string>();
    for (const raw of traceIds) {
      const id = zipkinTraceId(raw, operation);
      const key = id.padStart(32, "0");
      if (seen.has(key)) continue;
      seen.add(key);
      ids.push(id);
    }
    if (ids.length === 0) return [];
    if (ids.length === 1) {
      const one = await this.getTraceSpans(ids[0] as string, opts);
      return one === null ? [] : [one];
    }
    const byId = new Map<string, ZipkinSpan[]>();
    for (let i = 0; i < ids.length; i += TRACE_MANY_CHUNK) {
      const chunk = ids.slice(i, i + TRACE_MANY_CHUNK);
      let traces: ZipkinSpan[][];
      if (chunk.length === 1) {
        const one = await this.getTraceSpans(chunk[0] as string, opts);
        traces = one === null ? [] : [one];
      } else {
        const res = await this.#get(
          "/api/v2/traceMany",
          operation,
          { traceIds: chunk.join(",") },
          opts.signal,
        );
        traces = zipkinTracesFromBody(res.data);
      }
      for (const t of traces)
        byId.set((t[0] as ZipkinSpan).traceId.toLowerCase().padStart(32, "0"), t);
    }
    const out: ZipkinSpan[][] = [];
    for (const id of ids) {
      const t = byId.get(id.padStart(32, "0"));
      if (t !== undefined) out.push(t);
    }
    return out;
  }

  /**
   * SPEC §5 search: `service` → `serviceName`, `operation` → `spanName`, `tags` → exact tag
   * terms (see {@link ZipkinTraceQuery.tags}), durations in µs, `endTs` = `end` (default now),
   * `lookback` = `end - start` (last 1 h without `start`, like every tracing package), `limit`
   * default 20. Returns
   * one summary per trace (root = the span without a parent, else the earliest).
   */
  async searchTraces(
    query: TraceSearchQuery = {},
    opts: QueryCallOptions = {},
  ): Promise<TraceSummary[]> {
    const operation = "zipkin.searchTraces";
    const endMs = epochMs(query.end ?? new Date(this.#now()), "end", operation);
    let lookbackMs = DEFAULT_SEARCH_WINDOW_MS;
    if (query.start !== undefined) {
      const startMs = epochMs(query.start, "start", operation);
      if (startMs > endMs) throw new ValidationError(`${operation}: start must not be after end`);
      lookbackMs = Math.max(1, endMs - startMs);
    }
    const native: ZipkinTraceQuery = {
      endTs: endMs,
      lookbackMs,
      limit: query.limit ?? DEFAULT_SEARCH_LIMIT,
    };
    if (query.service !== undefined) native.serviceName = query.service;
    if (query.operation !== undefined) native.spanName = query.operation;
    if (query.tags !== undefined) native.tags = query.tags;
    if (query.minDurationMicros !== undefined) native.minDurationMicros = query.minDurationMicros;
    if (query.maxDurationMicros !== undefined) native.maxDurationMicros = query.maxDurationMicros;
    if (opts.signal !== undefined) native.signal = opts.signal;
    const traces = await this.getTraces(native);
    return traces.map(summarizeZipkinTrace).filter((s): s is TraceSummary => s !== null);
  }

  /** Service names that reported spans: `GET /api/v2/services` (sorted, lower-case). */
  async listServices(opts: QueryCallOptions = {}): Promise<string[]> {
    const res = await this.#get("/api/v2/services", "zipkin.listServices", {}, opts.signal);
    return stringsFromBody(res.data).sort();
  }

  /** Span names of a service: `GET /api/v2/spans?serviceName=` (sorted; unknown service → `[]`). */
  async listSpans(serviceName: string, opts: QueryCallOptions = {}): Promise<string[]> {
    const operation = "zipkin.listSpans";
    const res = await this.#get(
      "/api/v2/spans",
      operation,
      { serviceName: nonEmpty(serviceName, "serviceName", operation) },
      opts.signal,
    );
    return stringsFromBody(res.data).sort();
  }

  /** SPEC §5 alias of {@link listSpans}. */
  listOperations(service: string, opts: QueryCallOptions = {}): Promise<string[]> {
    return this.listSpans(service, opts);
  }

  /**
   * Remote services called by a service (`remoteEndpoint.serviceName` on its spans):
   * `GET /api/v2/remoteServices?serviceName=` (sorted).
   */
  async listRemoteServices(serviceName: string, opts: QueryCallOptions = {}): Promise<string[]> {
    const operation = "zipkin.listRemoteServices";
    const res = await this.#get(
      "/api/v2/remoteServices",
      operation,
      { serviceName: nonEmpty(serviceName, "serviceName", operation) },
      opts.signal,
    );
    return stringsFromBody(res.data).sort();
  }

  /**
   * Service dependency edges: `GET /api/v2/dependencies?endTs=&lookback=`, sorted by parent and
   * child, `errorCount` defaulting to 0. In-memory storage computes them on read; persistent
   * backends need the zipkin-dependencies job, so `[]` is normal there.
   */
  override async getDependencies(
    opts: ZipkinDependencyOptions = {},
  ): Promise<ZipkinDependencyLink[]> {
    const operation = "zipkin.getDependencies";
    const endMs = epochMs(opts.endTs ?? opts.end ?? new Date(this.#now()), "endTs", operation);
    let lookback = opts.lookbackMs;
    if (lookback === undefined && opts.start !== undefined)
      lookback = endMs - epochMs(opts.start, "start", operation);
    if (lookback !== undefined && (!Number.isFinite(lookback) || lookback <= 0))
      throw new ValidationError(`${operation}: lookback must be a positive duration`);
    const res = await this.#get(
      "/api/v2/dependencies",
      operation,
      { endTs: endMs, lookback: lookback === undefined ? undefined : Math.round(lookback) },
      opts.signal,
    );
    return dependenciesFromBody(res.data);
  }

  /**
   * Tag keys the server indexes for autocomplete: `GET /api/v2/autocompleteKeys` (the server's
   * `AUTOCOMPLETE_KEYS`, `[]` when unset).
   */
  async autocompleteKeys(opts: QueryCallOptions = {}): Promise<string[]> {
    const res = await this.#get(
      "/api/v2/autocompleteKeys",
      "zipkin.autocompleteKeys",
      {},
      opts.signal,
    );
    return stringsFromBody(res.data);
  }

  /**
   * Distinct values seen for an autocomplete key: `GET /api/v2/autocompleteValues?key=`
   * (sorted; `[]` for a key that is not in `AUTOCOMPLETE_KEYS`).
   */
  async autocompleteValues(key: string, opts: QueryCallOptions = {}): Promise<string[]> {
    const operation = "zipkin.autocompleteValues";
    const res = await this.#get(
      "/api/v2/autocompleteValues",
      operation,
      { key: nonEmpty(key, "key", operation) },
      opts.signal,
    );
    return stringsFromBody(res.data).sort();
  }

  /** Releases connections when this client owns them (no-op for `client.query`). Idempotent. */
  async close(): Promise<void> {
    if (this.#owned) await this.#transport.close();
  }
}

function blankToUndefined(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === "" ? undefined : value;
}

function durations(
  query: Pick<ZipkinTraceQuery, "minDurationMicros" | "maxDurationMicros">,
  operation: string,
): { min?: number; max?: number } {
  const check = (v: number | undefined, what: string): number | undefined => {
    if (v === undefined) return undefined;
    if (!Number.isFinite(v) || v < 0)
      throw new ValidationError(`${operation}: ${what} must be a non-negative number`);
    return Math.round(v);
  };
  const min = check(query.minDurationMicros, "minDurationMicros");
  const max = check(query.maxDurationMicros, "maxDurationMicros");
  if (max !== undefined && min === undefined)
    throw new ValidationError(`${operation}: maxDurationMicros needs minDurationMicros`);
  if (max !== undefined && min !== undefined && max < min)
    throw new ValidationError(
      `${operation}: maxDurationMicros must not be below minDurationMicros`,
    );
  const out: { min?: number; max?: number } = {};
  if (min !== undefined) out.min = min;
  if (max !== undefined) out.max = max;
  return out;
}
