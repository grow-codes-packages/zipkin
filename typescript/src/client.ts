// Service-owned: generated once by sdk-kit, then maintained in this repository.
// The skeleton targets the sdk-kit demo server: GET /health, GET /v1/ping, GET /version.

import {
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnsupportedError,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { ZipkinConfig, type ZipkinConfigOptions } from "./config.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** Client for Zipkin. */
export class ZipkinClient {
  /** The immutable configuration. */
  readonly config: ZipkinConfig;
  readonly #transport: HttpTransport;
  #closed = false;

  /** Accepts a {@link ZipkinConfig} or plain options. */
  constructor(config: ZipkinConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = ZipkinConfig.from(config);
    this.config = cfg;
    this.#transport = new HttpTransport({
      ...internals,
      config: cfg,
      defaultHeaders: (): Record<string, string> =>
        cfg.token === undefined ? {} : { authorization: `Bearer ${cfg.token.reveal()}` },
      knownSecrets: () => cfg.knownSecrets(),
    });
  }

  /** Builds a client from `GROW_ZIPKIN_*` (and vendor) environment variables. */
  static fromEnv(overrides: ZipkinConfigOptions = {}): ZipkinClient {
    return new ZipkinClient(ZipkinConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /**
   * Unauthenticated liveness: `GET /health`. Never throws for an unhealthy or unreachable
   * service (only `CANCELLED` and `CONFIG`).
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const res = await this.#transport.get<Record<string, unknown> | undefined>("/health", {
        operation: "health",
        ...options,
      });
      const { status, ...details } = res.data ?? {};
      return healthStatus(typeof status === "string" ? status : "ok", details);
    });
  }

  /** Authenticated round trip: `GET /v1/ping`. Returns the latency in milliseconds. */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    await this.#transport.get("/v1/ping", { operation: "ping", responseType: "none", ...options });
    return performance.now() - start;
  }

  /** Server version: `GET /version` → `{"version": "x.y.z"}`. */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    const res = await this.#transport.get<{ version?: unknown } | undefined>("/version", {
      operation: "serverVersion",
      allowStatus: [404],
      ...options,
    });
    const version = res.data?.version;
    if (res.status === 404 || typeof version !== "string") {
      throw new UnsupportedError("serverVersion: the service does not report a version");
    }
    return version;
  }

  /** Releases connections. Idempotent. */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    await this.#transport.close();
  }
}
