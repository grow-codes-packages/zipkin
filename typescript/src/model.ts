// Service-owned: the Zipkin v2 JSON span model, ingest normalization and the conversion of
// Zipkin spans into the SPEC §5 trace model.

import { ValidationError } from "./core/index.js";
import {
  normalizeTraceId,
  sortSpans,
  summarizeTrace,
  type DependencyLink,
  type Span,
  type SpanAttributes,
  type SpanEvent,
  type SpanKindName,
  type Trace,
  type TraceSummary,
} from "./tracing/index.js";

/** Zipkin span kind; absent means internal (local) work. */
export type ZipkinKind = "CLIENT" | "SERVER" | "PRODUCER" | "CONSUMER";

/** Network endpoint of a span (`localEndpoint` / `remoteEndpoint`). */
export interface ZipkinEndpoint {
  /** Lower-cased by the server on ingest. */
  serviceName?: string;
  ipv4?: string;
  ipv6?: string;
  port?: number;
}

/** A timestamped event on a span. */
export interface ZipkinAnnotation {
  /** Epoch microseconds. */
  timestamp: number;
  value: string;
}

/** A Zipkin v2 span exactly as the server stores and returns it (all tag values are strings). */
export interface ZipkinSpan {
  /** 16 or 32 lower-case hex characters. */
  traceId: string;
  /** 16 lower-case hex characters; absent on a root span. */
  parentId?: string;
  /** 16 lower-case hex characters. */
  id: string;
  kind?: ZipkinKind;
  /** Lower-cased by the server on ingest. */
  name?: string;
  /** Start, epoch microseconds. */
  timestamp?: number;
  /** Duration in microseconds. */
  duration?: number;
  localEndpoint?: ZipkinEndpoint;
  remoteEndpoint?: ZipkinEndpoint;
  annotations?: ZipkinAnnotation[];
  tags?: Record<string, string>;
  /** Force-store this span (B3 debug). */
  debug?: boolean;
  /** A server span that shares its id with the client span. */
  shared?: boolean;
}

/** Tag values accepted by {@link ZipkinReporter.report}; non-strings are converted to strings. */
export type ZipkinTagInput = string | number | boolean | null | undefined;

/**
 * Input of {@link ZipkinReporter.report}: a {@link ZipkinSpan} whose ids may be upper-case, whose
 * `kind` may be lower-case and whose tags may be numbers or booleans.
 */
export interface ZipkinSpanInput extends Omit<ZipkinSpan, "kind" | "tags"> {
  kind?: ZipkinKind | Lowercase<ZipkinKind>;
  tags?: Record<string, ZipkinTagInput>;
}

/** A dependency edge with Zipkin's `errorCount` (0 when the server omits it). */
export interface ZipkinDependencyLink extends DependencyLink {
  errorCount: number;
}

/** A span of the SPEC §5 model with the Zipkin-only fields kept. */
export interface ZipkinTraceSpan extends Span {
  localEndpoint?: ZipkinEndpoint;
  remoteEndpoint?: ZipkinEndpoint;
  shared?: boolean;
  debug?: boolean;
}

const TRACE_ID = /^(?:[0-9a-f]{16}|[0-9a-f]{32})$/;
const SPAN_ID = /^[0-9a-f]{16}$/;
const KINDS: ReadonlySet<string> = new Set(["CLIENT", "SERVER", "PRODUCER", "CONSUMER"]);

/**
 * Lower-cases and validates a Zipkin trace id (16 or 32 hex characters, not all zero). Throws
 * `ValidationError` without a request otherwise.
 */
export function zipkinTraceId(id: string, operation = "zipkin"): string {
  const lower = id.trim().toLowerCase();
  if (!TRACE_ID.test(lower) || /^0+$/.test(lower))
    throw new ValidationError(`${operation}: trace id must be 16 or 32 hex characters`);
  return lower;
}

function spanId(id: unknown, what: string, operation: string): string {
  const lower = typeof id === "string" ? id.trim().toLowerCase() : "";
  if (!SPAN_ID.test(lower) || /^0+$/.test(lower))
    throw new ValidationError(`${operation}: ${what} must be 16 hex characters`);
  return lower;
}

/** Converts a tag value to Zipkin's string form (`true`, `7`, `1.5`, JSON for anything else). */
export function tagValue(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint")
    return String(value);
  return JSON.stringify(value);
}

function micros(value: unknown, what: string, operation: string): number | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0)
    throw new ValidationError(
      `${operation}: ${what} must be a non-negative number of microseconds`,
    );
  return Math.round(value);
}

function endpoint(
  input: ZipkinEndpoint | undefined,
  operation: string,
): ZipkinEndpoint | undefined {
  if (input === undefined) return undefined;
  const out: ZipkinEndpoint = {};
  if (input.serviceName !== undefined && input.serviceName !== "")
    out.serviceName = input.serviceName;
  if (input.ipv4 !== undefined && input.ipv4 !== "") out.ipv4 = input.ipv4;
  if (input.ipv6 !== undefined && input.ipv6 !== "") out.ipv6 = input.ipv6;
  if (input.port !== undefined && input.port !== 0) {
    if (!Number.isInteger(input.port) || input.port < 0 || input.port > 65535)
      throw new ValidationError(`${operation}: endpoint port must be 0..65535`);
    out.port = input.port;
  }
  return Object.keys(out).length === 0 ? undefined : out;
}

/**
 * Normalizes one span for ingest (the rules Zipkin enforces, applied locally so one bad span
 * never makes the server reject the whole batch): ids lower-cased and validated, `kind`
 * upper-cased, timestamps rounded to integer microseconds, tag values converted to strings,
 * empty fields dropped. Throws `ValidationError` naming the problem.
 */
export function normalizeZipkinSpan(
  input: ZipkinSpanInput,
  operation = "zipkin.report",
): ZipkinSpan {
  const raw: unknown = input;
  if (typeof raw !== "object" || raw === null)
    throw new ValidationError(`${operation}: span must be an object`);
  const out: ZipkinSpan = {
    traceId: zipkinTraceId(typeof input.traceId === "string" ? input.traceId : "", operation),
    id: spanId(input.id, "span id", operation),
  };
  if (input.parentId !== undefined && input.parentId !== "") {
    const parent = spanId(input.parentId, "parentId", operation);
    out.parentId = parent;
  }
  if (input.kind !== undefined) {
    const kind = input.kind.toUpperCase();
    if (!KINDS.has(kind))
      throw new ValidationError(`${operation}: kind must be CLIENT, SERVER, PRODUCER or CONSUMER`);
    out.kind = kind as ZipkinKind;
  }
  if (input.name !== undefined && input.name !== "") out.name = input.name;
  const timestamp = micros(input.timestamp, "timestamp", operation);
  if (timestamp !== undefined && timestamp > 0) out.timestamp = timestamp;
  const duration = micros(input.duration, "duration", operation);
  if (duration !== undefined && duration > 0) out.duration = duration;
  const local = endpoint(input.localEndpoint, operation);
  if (local !== undefined) out.localEndpoint = local;
  const remote = endpoint(input.remoteEndpoint, operation);
  if (remote !== undefined) out.remoteEndpoint = remote;
  const annotations = (input.annotations ?? []).map((a) => {
    if (typeof a !== "object" || typeof a.value !== "string" || a.value === "")
      throw new ValidationError(`${operation}: annotation value must be a non-empty string`);
    const ts = micros(a.timestamp, "annotation timestamp", operation);
    if (ts === undefined || ts === 0)
      throw new ValidationError(`${operation}: annotation timestamp is required`);
    return { timestamp: ts, value: a.value };
  });
  if (annotations.length > 0) out.annotations = annotations;
  const tags: Record<string, string> = {};
  for (const [key, value] of Object.entries(input.tags ?? {})) {
    if (value === undefined || value === null) continue;
    tags[key] = tagValue(value);
  }
  if (Object.keys(tags).length > 0) out.tags = tags;
  if (input.debug === true) out.debug = true;
  if (input.shared === true) out.shared = true;
  return out;
}

// ---------------------------------------------------------------------------------------------
// Decoding responses

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Keeps the well-formed spans of a response body (`ZipkinSpan[]`). */
export function zipkinSpansFromBody(body: unknown): ZipkinSpan[] {
  if (!Array.isArray(body)) return [];
  return body.filter(
    (s): s is ZipkinSpan =>
      isObject(s) && typeof s.traceId === "string" && typeof s.id === "string",
  );
}

/** Keeps the well-formed traces of a response body (`ZipkinSpan[][]`), dropping empty ones. */
export function zipkinTracesFromBody(body: unknown): ZipkinSpan[][] {
  if (!Array.isArray(body)) return [];
  return body.map(zipkinSpansFromBody).filter((t) => t.length > 0);
}

/** Decodes a string list response (`string[]`). */
export function stringsFromBody(body: unknown): string[] {
  return Array.isArray(body) ? body.filter((s): s is string => typeof s === "string") : [];
}

/** Decodes `/api/v2/dependencies`, defaulting `errorCount` to 0, sorted by parent then child. */
export function dependenciesFromBody(body: unknown): ZipkinDependencyLink[] {
  if (!Array.isArray(body)) return [];
  return body
    .filter(
      (l): l is Record<string, unknown> & { parent: string; child: string } =>
        isObject(l) && typeof l.parent === "string" && typeof l.child === "string",
    )
    .map((l) => ({
      parent: l.parent,
      child: l.child,
      callCount: typeof l.callCount === "number" ? l.callCount : 0,
      errorCount: typeof l.errorCount === "number" ? l.errorCount : 0,
    }))
    .sort((a, b) =>
      a.parent !== b.parent ? (a.parent < b.parent ? -1 : 1) : a.child < b.child ? -1 : 1,
    );
}

// ---------------------------------------------------------------------------------------------
// Zipkin → SPEC §5 model

const KIND_NAMES: Record<string, SpanKindName> = {
  CLIENT: "client",
  SERVER: "server",
  PRODUCER: "producer",
  CONSUMER: "consumer",
};

/**
 * Decodes an annotation into an event: a value of the form `{"<name>": {…attributes}}` (how the
 * {@link ZipkinSpanExporter} encodes an event with attributes) becomes that name and attributes;
 * any other value is the event name with no attributes.
 */
export function annotationToEvent(annotation: ZipkinAnnotation): SpanEvent {
  const value = annotation.value;
  if (value.startsWith("{") && value.endsWith("}")) {
    try {
      const parsed = JSON.parse(value) as unknown;
      if (isObject(parsed)) {
        const keys = Object.keys(parsed);
        const attributes = keys.length === 1 ? parsed[keys[0] as string] : undefined;
        if (isObject(attributes))
          return {
            name: keys[0] as string,
            timeMicros: annotation.timestamp,
            attributes: attributes as SpanAttributes,
          };
      }
    } catch {
      // not JSON: the value is the name
    }
  }
  return { name: value, timeMicros: annotation.timestamp, attributes: {} };
}

/**
 * Converts a Zipkin span into the SPEC §5 model: trace id padded to 32 characters, `kind`
 * lower-cased (absent → `internal`), `serviceName` = `localEndpoint.serviceName` (or `""`),
 * `attributes` = tags, annotations → events, status from the `error` tag (error, message = its
 * value) or `otel.status_code` (`OK`/`ERROR`), no links. Endpoints, `shared` and `debug` are kept.
 */
export function zipkinSpanToSpan(span: ZipkinSpan): ZipkinTraceSpan {
  const tags = span.tags ?? {};
  const start = span.timestamp ?? 0;
  const duration = span.duration ?? 0;
  let status: Span["status"] = { code: "unset" };
  const error = tags.error;
  const otelStatus = tags["otel.status_code"]?.toUpperCase();
  if (error !== undefined)
    status = error === "" ? { code: "error" } : { code: "error", message: error };
  else if (otelStatus === "ERROR") status = { code: "error" };
  else if (otelStatus === "OK") status = { code: "ok" };
  const out: ZipkinTraceSpan = {
    traceId: normalizeTraceId(span.traceId),
    spanId: span.id.toLowerCase(),
    name: span.name ?? "",
    kind: span.kind === undefined ? "internal" : (KIND_NAMES[span.kind] ?? "unspecified"),
    serviceName: span.localEndpoint?.serviceName ?? "",
    startMicros: start,
    endMicros: start + duration,
    durationMicros: duration,
    status,
    attributes: { ...tags },
    events: (span.annotations ?? []).map(annotationToEvent),
    links: [],
  };
  if (span.parentId !== undefined && span.parentId !== "")
    out.parentSpanId = span.parentId.toLowerCase();
  if (span.localEndpoint !== undefined) out.localEndpoint = span.localEndpoint;
  if (span.remoteEndpoint !== undefined) out.remoteEndpoint = span.remoteEndpoint;
  if (span.shared === true) out.shared = true;
  if (span.debug === true) out.debug = true;
  return out;
}

/** Converts the spans of one Zipkin trace into a {@link Trace} (spans sorted); `null` when empty. */
export function zipkinTraceToTrace(spans: readonly ZipkinSpan[]): Trace | null {
  const first = spans[0];
  if (first === undefined) return null;
  return {
    traceId: normalizeTraceId(first.traceId),
    spans: sortSpans(spans.map(zipkinSpanToSpan)),
  };
}

/** Summarises one Zipkin trace (root = span without a parent in the trace, else the earliest). */
export function summarizeZipkinTrace(spans: readonly ZipkinSpan[]): TraceSummary | null {
  const trace = zipkinTraceToTrace(spans);
  if (trace === null) return null;
  const summary = summarizeTrace(trace);
  if (summary.rootName === undefined) {
    const earliest = trace.spans[0] as Span;
    summary.rootName = earliest.name;
    summary.rootServiceName = earliest.serviceName;
  }
  return summary;
}
