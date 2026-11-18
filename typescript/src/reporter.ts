// Service-owned: ZipkinReporter — sends Zipkin v2 JSON spans to POST /api/v2/spans.

import { gzipSync } from "node:zlib";
import { ConfigError, HttpTransport, type TransportOptions } from "./core/index.js";
import { ZipkinConfig, type ZipkinConfigOptions } from "./config.js";
import { normalizeZipkinSpan, type ZipkinSpanInput } from "./model.js";

/** Test seams (undici `MockAgent`, fake sleep, clock). Not part of the stable API. */
export type ZipkinInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** Anything that carries a configured Zipkin transport (a {@link ZipkinClient}). */
export interface TransportSource {
  readonly transport: HttpTransport;
  readonly config: ZipkinConfig;
}

/** Builds the HTTP transport for a Zipkin config (extra headers are the only credentials). */
export function createZipkinTransport(
  config: ZipkinConfig,
  internals: ZipkinInternals = {},
): HttpTransport {
  return new HttpTransport({ ...internals, config, knownSecrets: () => config.knownSecrets() });
}

/** @internal */
export function isTransportSource(value: unknown): value is TransportSource {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as { transport?: unknown }).transport instanceof HttpTransport &&
    (value as { config?: unknown }).config instanceof ZipkinConfig
  );
}

/** Default number of spans per POST (SPEC batch default). */
export const DEFAULT_MAX_BATCH_SIZE = 512;

/** Options of {@link ZipkinReporter}. */
export interface ZipkinReporterOptions {
  /** `gzip` compresses each request body (`Content-Encoding: gzip`). Default `none`. */
  compression?: "gzip" | "none";
  /** Spans per POST; larger reports are split. Default 512. */
  maxBatchSize?: number;
  /** Ingest URL; default the config's `endpoint` (`<url>/api/v2/spans`). */
  endpoint?: string;
}

/** Per-call options of {@link ZipkinReporter.report}. */
export interface ReportOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/**
 * Reports spans to Zipkin without OpenTelemetry: `POST /api/v2/spans` with a JSON array of v2
 * spans (`202 Accepted`). Spans are normalized locally first ({@link normalizeZipkinSpan}), so an
 * invalid span throws `ValidationError` before anything is sent. Ingest is idempotent (Zipkin
 * merges spans by id), so 5xx, 429 and network errors are retried; a 400 or 413 is permanent.
 *
 * Get one from {@link ZipkinClient.reporter} (shares the client's connections) or construct it
 * from a config (owns its connections; call {@link close}).
 */
export class ZipkinReporter {
  /** Ingest URL used by {@link report}. */
  readonly endpoint: string;
  /** Request body compression. */
  readonly compression: "gzip" | "none";
  /** Spans per POST. */
  readonly maxBatchSize: number;
  readonly #transport: HttpTransport;
  readonly #owned: boolean;

  /**
   * @param source a {@link ZipkinClient} (shared transport) or config options (own transport).
   * @param options compression, batch size and endpoint override.
   * @param internals test seams.
   */
  constructor(
    source: TransportSource | ZipkinConfigOptions = {},
    options: ZipkinReporterOptions = {},
    internals: ZipkinInternals = {},
  ) {
    let config: ZipkinConfig;
    if (isTransportSource(source)) {
      config = source.config;
      this.#transport = source.transport;
      this.#owned = false;
    } else {
      config = ZipkinConfig.from(source);
      this.#transport = createZipkinTransport(config, internals);
      this.#owned = true;
    }
    const compression: string = options.compression ?? "none";
    if (compression !== "gzip" && compression !== "none")
      throw new ConfigError("zipkin.reporter: compression must be gzip or none");
    const maxBatchSize = options.maxBatchSize ?? DEFAULT_MAX_BATCH_SIZE;
    if (!Number.isInteger(maxBatchSize) || maxBatchSize <= 0)
      throw new ConfigError("zipkin.reporter: maxBatchSize must be a positive integer");
    const endpoint = options.endpoint ?? config.endpoint;
    if (!/^https?:\/\//i.test(endpoint))
      throw new ConfigError("zipkin.reporter: endpoint must be an http or https URL");
    this.endpoint = endpoint;
    this.compression = compression;
    this.maxBatchSize = maxBatchSize;
  }

  /** Builds a standalone reporter from `GROW_ZIPKIN_*` / `ZIPKIN_URL` / `OTEL_EXPORTER_ZIPKIN_ENDPOINT`. */
  static fromEnv(
    overrides: ZipkinConfigOptions = {},
    options: ZipkinReporterOptions = {},
  ): ZipkinReporter {
    return new ZipkinReporter(ZipkinConfig.fromEnv(overrides), options);
  }

  /**
   * Sends spans: an empty list sends nothing; more than `maxBatchSize` spans are sent in several
   * POSTs, in order. Spans are queryable once the call resolves (immediately with in-memory
   * storage; persistent backends may lag — use `query.waitForTrace`).
   */
  async report(spans: readonly ZipkinSpanInput[], options: ReportOptions = {}): Promise<void> {
    const normalized = spans.map((s) => normalizeZipkinSpan(s));
    for (let i = 0; i < normalized.length; i += this.maxBatchSize) {
      const batch = normalized.slice(i, i + this.maxBatchSize);
      const json = JSON.stringify(batch);
      const headers: Record<string, string> = { "content-type": "application/json" };
      let body: string | Uint8Array = json;
      if (this.compression === "gzip") {
        body = new Uint8Array(gzipSync(json));
        headers["content-encoding"] = "gzip";
      }
      await this.#transport.post(this.endpoint, {
        operation: "zipkin.report",
        headers,
        body,
        idempotent: true,
        responseType: "none",
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      });
    }
  }

  /** Releases connections when this reporter owns them (no-op for `client.reporter`). Idempotent. */
  async close(): Promise<void> {
    if (this.#owned) await this.#transport.close();
  }
}
