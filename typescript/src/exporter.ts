// Service-owned: ZipkinSpanExporter — an OpenTelemetry SpanExporter that sends Zipkin v2 JSON
// through ZipkinReporter (no dependency on the deprecated OpenTelemetry Zipkin exporters).

import {
  SpanKind,
  SpanStatusCode,
  isValidSpanId,
  type AttributeValue,
  type Attributes,
  type HrTime,
} from "@opentelemetry/api";
import { ExportResultCode, type ExportResult } from "@opentelemetry/core";
import type { ReadableSpan, SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ZipkinConfig, type ZipkinConfigOptions } from "./config.js";
import { tagValue, type ZipkinKind, type ZipkinSpan } from "./model.js";
import { ZipkinReporter, type ZipkinInternals } from "./reporter.js";
import { hrTimeToMicros } from "./tracing/index.js";

/** Service name used when the resource has no `service.name`. */
export const UNKNOWN_SERVICE = "unknown_service";

/** Span attributes that name the remote service of a CLIENT/PRODUCER span, by precedence. */
export const REMOTE_SERVICE_KEYS: readonly string[] = Object.freeze([
  "peer.service",
  "server.address",
  "net.peer.name",
  "network.peer.address",
]);

const KINDS: Partial<Record<SpanKind, ZipkinKind>> = {
  [SpanKind.SERVER]: "SERVER",
  [SpanKind.CLIENT]: "CLIENT",
  [SpanKind.PRODUCER]: "PRODUCER",
  [SpanKind.CONSUMER]: "CONSUMER",
};

/** Converts an OpenTelemetry attribute value to a Zipkin tag (arrays become JSON). */
export function attributeToTag(value: AttributeValue): string {
  return tagValue(value);
}

function durationMicros(duration: HrTime): number {
  return Math.max(1, Math.round(duration[0] * 1_000_000 + duration[1] / 1000));
}

function attributesToTags(attributes: Attributes | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(attributes ?? {})) {
    if (value === undefined) continue;
    out[key] = attributeToTag(value);
  }
  return out;
}

function remoteServiceName(attributes: Attributes): string | undefined {
  for (const key of REMOTE_SERVICE_KEYS) {
    const value = attributes[key];
    if (typeof value === "string" && value !== "") return value;
  }
  return undefined;
}

/**
 * Maps a finished OpenTelemetry span to a Zipkin v2 span (identical in every language of this
 * SDK; based on the OpenTelemetry Zipkin exporter specification):
 *
 * | Zipkin | OpenTelemetry |
 * | - | - |
 * | `traceId`, `id` | span context ids (lower-case hex, 32 / 16) |
 * | `parentId` | parent span id when valid, else omitted |
 * | `kind` | SERVER/CLIENT/PRODUCER/CONSUMER 1:1; INTERNAL → omitted |
 * | `name` | span name (the server lower-cases it) |
 * | `timestamp` | start time, epoch µs (truncated) |
 * | `duration` | end − start in µs, rounded, at least 1 |
 * | `localEndpoint.serviceName` | resource `service.name`, else `unknown_service` |
 * | `remoteEndpoint` | CLIENT/PRODUCER only: `{serviceName}` = first non-empty string of `peer.service`, `server.address`, `net.peer.name`, `network.peer.address`; omitted when none is set |
 * | `tags` | span attributes (strings as-is, numbers/booleans `String(v)`, arrays JSON); then `otel.scope.name` / `otel.scope.version`; `otel.dropped_attributes_count` / `otel.dropped_events_count` when > 0; status ERROR → `otel.status_code=ERROR` + `error=<message or "">`, OK → `otel.status_code=OK` (status tags win). Resource attributes are not copied |
 * | `annotations` | events: `timestamp` = event time µs, `value` = event name, or the JSON text `{"<name>":{…attributes}}` (attribute values keep their JSON type) when the event has attributes |
 * | `debug`, `shared` | never set |
 *
 * Links, trace state and resource attributes other than `service.name` are not mapped.
 */
export function toZipkinSpan(span: ReadableSpan): ZipkinSpan {
  const ctx = span.spanContext();
  const resourceAttrs = span.resource.attributes;
  const serviceName = resourceAttrs["service.name"];
  const out: ZipkinSpan = {
    traceId: ctx.traceId.toLowerCase(),
    id: ctx.spanId.toLowerCase(),
  };
  const parent = span.parentSpanContext?.spanId;
  if (parent !== undefined && isValidSpanId(parent)) out.parentId = parent.toLowerCase();
  const kind = KINDS[span.kind];
  if (kind !== undefined) out.kind = kind;
  out.name = span.name;
  out.timestamp = hrTimeToMicros(span.startTime);
  out.duration = durationMicros(span.duration);
  out.localEndpoint = {
    serviceName:
      typeof serviceName === "string" && serviceName !== "" ? serviceName : UNKNOWN_SERVICE,
  };
  if (kind === "CLIENT" || kind === "PRODUCER") {
    const remote = remoteServiceName(span.attributes);
    if (remote !== undefined) out.remoteEndpoint = { serviceName: remote };
  }
  if (span.events.length > 0) {
    out.annotations = span.events.map((e) => {
      const attrs = Object.fromEntries(
        Object.entries(e.attributes ?? {}).filter(([, v]) => v !== undefined),
      );
      return {
        timestamp: hrTimeToMicros(e.time),
        value: Object.keys(attrs).length === 0 ? e.name : JSON.stringify({ [e.name]: attrs }),
      };
    });
  }
  const tags = attributesToTags(span.attributes);
  const scope = span.instrumentationScope;
  if (scope.name !== "") tags["otel.scope.name"] = scope.name;
  if (scope.version !== undefined && scope.version !== "")
    tags["otel.scope.version"] = scope.version;
  if (span.droppedAttributesCount > 0)
    tags["otel.dropped_attributes_count"] = String(span.droppedAttributesCount);
  if (span.droppedEventsCount > 0)
    tags["otel.dropped_events_count"] = String(span.droppedEventsCount);
  if (span.status.code === SpanStatusCode.ERROR) {
    tags["otel.status_code"] = "ERROR";
    tags.error = span.status.message ?? "";
  } else if (span.status.code === SpanStatusCode.OK) {
    tags["otel.status_code"] = "OK";
  }
  if (Object.keys(tags).length > 0) out.tags = tags;
  return out;
}

/** Options of {@link ZipkinSpanExporter}. */
export interface ZipkinSpanExporterOptions {
  /** Send through this reporter (not closed by {@link ZipkinSpanExporter.shutdown}). */
  reporter?: ZipkinReporter;
  /** Otherwise: connection settings of an owned reporter (default `ZipkinConfig.fromEnv()`). */
  config?: ZipkinConfigOptions;
  /** Request body compression of an owned reporter. Default `gzip`. */
  compression?: "gzip" | "none";
  /** Spans per POST of an owned reporter. Default 512. */
  maxBatchSize?: number;
  /** Called with every failed export (the batch is dropped). Never throws. */
  onError?: (error: Error) => void;
}

/**
 * OpenTelemetry `SpanExporter` for Zipkin: maps each span with {@link toZipkinSpan} and sends
 * the batch with a {@link ZipkinReporter} (`POST /api/v2/spans`, JSON v2, gzip by default,
 * retries on 5xx/429/network errors). A failed batch reports `FAILED` to the span processor and
 * is dropped.
 */
export class ZipkinSpanExporter implements SpanExporter {
  /** The reporter used to send spans. */
  readonly reporter: ZipkinReporter;
  readonly #owned: boolean;
  readonly #onError: ((error: Error) => void) | undefined;
  readonly #pending = new Set<Promise<void>>();
  #shutdown: Promise<void> | null = null;

  constructor(options: ZipkinSpanExporterOptions = {}, internals: ZipkinInternals = {}) {
    if (options.reporter !== undefined) {
      this.reporter = options.reporter;
      this.#owned = false;
    } else {
      const config =
        options.config instanceof ZipkinConfig
          ? options.config
          : ZipkinConfig.fromEnv(options.config ?? {});
      this.reporter = new ZipkinReporter(
        config,
        {
          compression: options.compression ?? "gzip",
          ...(options.maxBatchSize === undefined ? {} : { maxBatchSize: options.maxBatchSize }),
        },
        internals,
      );
      this.#owned = true;
    }
    this.#onError = options.onError;
  }

  /** Exports one batch; `resultCallback` receives SUCCESS or FAILED (with the error). */
  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    if (this.#shutdown !== null) {
      resultCallback({
        code: ExportResultCode.FAILED,
        error: new Error("zipkin exporter is shut down"),
      });
      return;
    }
    const task = (async (): Promise<ExportResult> => {
      try {
        await this.reporter.report(spans.map(toZipkinSpan));
        return { code: ExportResultCode.SUCCESS };
      } catch (err) {
        const error = err instanceof Error ? err : new Error(String(err));
        try {
          this.#onError?.(error);
        } catch {
          // hooks never break exporting
        }
        return { code: ExportResultCode.FAILED, error };
      }
    })();
    const done = task.then((result) => {
      try {
        resultCallback(result);
      } catch {
        // a throwing callback must not break flush/shutdown
      }
    });
    this.#pending.add(done);
    void done.finally(() => this.#pending.delete(done));
  }

  /** Waits for every export in flight. */
  async forceFlush(): Promise<void> {
    await Promise.all([...this.#pending]);
  }

  /** Waits for in-flight exports, then closes an owned reporter. Idempotent. */
  shutdown(): Promise<void> {
    this.#shutdown ??= (async () => {
      await this.forceFlush();
      if (this.#owned) await this.reporter.close();
    })();
    return this.#shutdown;
  }
}
