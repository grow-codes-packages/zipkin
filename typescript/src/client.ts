// Service-owned: ZipkinClient — health, ping and version of a Zipkin server plus the query
// client, the span reporter and the tracing preset.

import {
  ConfigError,
  type HealthStatus,
  type HttpTransport,
  UnsupportedError,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { ZipkinConfig, type ZipkinConfigOptions } from "./config.js";
import { ZipkinQueryClient } from "./query.js";
import {
  ZipkinReporter,
  createZipkinTransport,
  type ZipkinInternals,
  type ZipkinReporterOptions,
} from "./reporter.js";
import {
  initTracing,
  tracingConfigFromEnv,
  type ZipkinInitTracingOptions,
  type ZipkinTracingHandle,
} from "./setup.js";
import type { TracingConfig } from "./tracing/index.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep, clock). Not part of the stable API. */
export type ClientInternals = ZipkinInternals;

/** Build information of the server (`GET /info`). */
export interface ZipkinServerInfo {
  /** e.g. `3.6.1`. */
  version?: string;
  /** Short git commit. */
  commit?: string;
}

/**
 * Client for Zipkin (port 9411): health, ping and server version, plus the query API
 * ({@link query}), the direct span reporter ({@link reporter}) and tracing setup
 * ({@link initTracing}). {@link close} also shuts down every tracing handle created through
 * this client.
 */
export class ZipkinClient {
  /** The immutable configuration. */
  readonly config: ZipkinConfig;
  readonly #transport: HttpTransport;
  readonly #internals: ClientInternals;
  readonly #handles = new Set<ZipkinTracingHandle>();
  #query: ZipkinQueryClient | undefined;
  #reporter: ZipkinReporter | undefined;
  #closed = false;

  /** Accepts a {@link ZipkinConfig} or plain options. */
  constructor(config: ZipkinConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = ZipkinConfig.from(config);
    this.config = cfg;
    this.#internals = internals;
    this.#transport = createZipkinTransport(cfg, internals);
  }

  /** Builds a client from `GROW_ZIPKIN_*` (and vendor) environment variables. */
  static fromEnv(overrides: ZipkinConfigOptions = {}): ZipkinClient {
    return new ZipkinClient(ZipkinConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /** The trace query client (shares this client's connections). */
  get query(): ZipkinQueryClient {
    this.#query ??= new ZipkinQueryClient(this);
    return this.#query;
  }

  /** The span reporter (shares this client's connections; no compression, 512 spans per POST). */
  get reporter(): ZipkinReporter {
    this.#reporter ??= new ZipkinReporter(this);
    return this.#reporter;
  }

  /** A reporter with other options that shares this client's connections. */
  createReporter(options: ZipkinReporterOptions = {}): ZipkinReporter {
    return new ZipkinReporter(this, options);
  }

  /**
   * Liveness: `GET /health` → `{"status":"UP"}`. A 503 (`DOWN`, storage unavailable) gives
   * `healthy: false` without retries. Never throws for an unhealthy or unreachable service (only
   * `CANCELLED` and `CONFIG`).
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const res = await this.#transport.get<Record<string, unknown> | undefined>("/health", {
        operation: "health",
        allowStatus: [503],
        ...options,
      });
      if (res.status === 503) return healthStatus("DOWN", { httpStatus: 503 }, false);
      const body = res.data ?? {};
      const status = typeof body.status === "string" ? body.status : "UP";
      const details: Record<string, unknown> = {};
      const zipkin = body.zipkin as { status?: unknown; details?: unknown } | undefined;
      if (typeof zipkin?.status === "string") details.zipkin = zipkin.status;
      if (zipkin?.details !== undefined) details.storage = zipkin.details;
      return healthStatus(status, details);
    });
  }

  /**
   * Round trip through the query API and storage: `GET /api/v2/services` (through a gateway this
   * also proves the auth headers). Returns the latency in ms.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    await this.#transport.get("/api/v2/services", {
      operation: "ping",
      responseType: "none",
      ...options,
    });
    return performance.now() - start;
  }

  /** Build information: `GET /info` → `{"zipkin":{"version","commit"}}`. */
  async serverInfo(options: CallOptions = {}): Promise<ZipkinServerInfo> {
    const res = await this.#transport.get<{ zipkin?: Record<string, unknown> } | undefined>(
      "/info",
      { operation: "serverVersion", allowStatus: [404], ...options },
    );
    const zipkin = res.status === 404 ? undefined : res.data?.zipkin;
    const info: ZipkinServerInfo = {};
    if (typeof zipkin?.version === "string" && zipkin.version !== "") info.version = zipkin.version;
    if (typeof zipkin?.commit === "string" && zipkin.commit !== "") info.commit = zipkin.commit;
    return info;
  }

  /**
   * Server version (e.g. `3.6.1`) from `GET /info`. Throws `UnsupportedError` when the server
   * (or a gateway) does not report one.
   */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    const info = await this.serverInfo(options);
    if (info.version === undefined)
      throw new UnsupportedError("serverVersion: the service does not report a version");
    return info.version;
  }

  /**
   * Starts OpenTelemetry tracing to this Zipkin (see the package-level `initTracing`). The
   * native exporter uses this client's endpoint, headers, TLS, proxy, retries, hooks and logger;
   * an explicit `config.endpoint` wins. The handle is shut down by {@link close}. On a closed
   * client it throws `ConfigError` (`CONFIG`): starting tracing is a local setup step, not a
   * request, so it does not fail with `CANCELLED` like the calls.
   */
  initTracing(config: TracingConfig, options: ZipkinInitTracingOptions = {}): ZipkinTracingHandle {
    if (this.#closed) throw new ConfigError("initTracing: client is closed");
    const env = options.env;
    const protocol =
      config.protocol ??
      (env === false ? undefined : tracingConfigFromEnv({}, env ?? process.env).protocol) ??
      "native";
    const native = protocol === "native";
    const handle = initTracing(
      native && config.endpoint === undefined
        ? { ...config, endpoint: this.config.endpoint }
        : config,
      {
        clientConfig: this.config,
        internals: this.#internals,
        ...options,
      },
    );
    this.#handles.add(handle);
    return handle;
  }

  /**
   * Shuts down the tracing handles created through this client (flushing their batch
   * processors), then releases connections. Idempotent.
   */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    const handles = [...this.#handles].map((h) => h.shutdown());
    this.#handles.clear();
    await Promise.allSettled(handles);
    await this.#transport.close();
  }
}
