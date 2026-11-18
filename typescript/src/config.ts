// Service-owned: ZipkinConfig — connection settings of the Zipkin query and ingest APIs.

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  type RetryPolicy,
  type TlsOptions,
  describeCommonConfig,
  isSensitiveHeader,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  validateCommonConfig,
} from "./core/index.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Path of the Zipkin v2 span ingest API. */
export const SPANS_PATH = "/api/v2/spans";

/** Options accepted by {@link ZipkinConfig} (all optional). */
export interface ZipkinConfigOptions extends CommonConfigOptions {
  /**
   * Base URL of the Zipkin server (port 9411). A gateway path prefix is kept
   * (`https://gw.example/zipkin` → `https://gw.example/zipkin/api/v2/...`). Stock Zipkin has no
   * authentication: put gateway credentials in `headers` (they are redacted everywhere).
   * Env `GROW_ZIPKIN_URL`, then `ZIPKIN_URL`, then the base of `OTEL_EXPORTER_ZIPKIN_ENDPOINT`.
   * Default `http://127.0.0.1:9411`.
   */
  url?: string;
  /**
   * Full span ingest URL used by {@link ZipkinReporter} and the tracing exporter. Env
   * `GROW_ZIPKIN_ENDPOINT`, then `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (used verbatim). Default
   * `<url>/api/v2/spans`.
   */
  endpoint?: string;
}

/** Vendor environment variables read after `GROW_ZIPKIN_*`, in order. */
export const VENDOR_ENV: CommonVendorEnv & { endpoint: readonly string[] } = {
  url: ["ZIPKIN_URL"],
  endpoint: ["OTEL_EXPORTER_ZIPKIN_ENDPOINT"],
};

function isHttpUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

/**
 * `<base>/api/v2/spans`, appended once: a base that already ends with the spans path is kept, a
 * gateway prefix is preserved, trailing slashes are dropped.
 */
export function spansEndpoint(base: string): string {
  const trimmed = base.replace(/\/+$/, "");
  return trimmed.endsWith(SPANS_PATH) ? trimmed : `${trimmed}${SPANS_PATH}`;
}

/** The server base URL of a spans endpoint: strips one trailing `/api/v2/spans` (and slashes). */
export function baseFromEndpoint(endpoint: string): string {
  const trimmed = endpoint.replace(/\/+$/, "");
  return trimmed.endsWith(SPANS_PATH) ? trimmed.slice(0, -SPANS_PATH.length) : trimmed;
}

/** Immutable configuration of {@link ZipkinClient}. */
export class ZipkinConfig implements CommonConfig {
  /** Default server URL. */
  static readonly DEFAULT_URL = "http://127.0.0.1:9411";
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_ZIPKIN_";

  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** Span ingest URL (`<url>/api/v2/spans` unless set). */
  readonly endpoint: string;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: ZipkinConfigOptions = {}) {
    const common = resolveCommonConfig(options, ZipkinConfig.DEFAULT_URL);
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    this.endpoint =
      options.endpoint !== undefined && options.endpoint !== ""
        ? options.endpoint
        : spansEndpoint(this.url);
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: ZipkinConfigOptions = {}): ZipkinConfig {
    if (options instanceof ZipkinConfig) return options;
    return new ZipkinConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_ZIPKIN_<NAME>` > vendor variables > defaults.
   *
   * - `url`: `GROW_ZIPKIN_URL` > `ZIPKIN_URL` > `OTEL_EXPORTER_ZIPKIN_ENDPOINT` without its
   *   trailing `/api/v2/spans` > `http://127.0.0.1:9411`.
   * - `endpoint`: `GROW_ZIPKIN_ENDPOINT` > `OTEL_EXPORTER_ZIPKIN_ENDPOINT` > `<url>/api/v2/spans`.
   * - Timeouts, retries, TLS and proxy: the common `GROW_ZIPKIN_*` variables (SPEC §2.2).
   */
  static fromEnv(overrides: ZipkinConfigOptions = {}, env: EnvMap = process.env): ZipkinConfig {
    const reader = new EnvReader(ZipkinConfig.ENV_PREFIX, env);
    const fromEnv: ZipkinConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const endpoint = reader.string("ENDPOINT", VENDOR_ENV.endpoint);
    if (endpoint !== undefined) fromEnv.endpoint = endpoint;
    const otelEndpoint = env.OTEL_EXPORTER_ZIPKIN_ENDPOINT;
    if (fromEnv.url === undefined && otelEndpoint !== undefined && otelEndpoint !== "")
      fromEnv.url = baseFromEndpoint(otelEndpoint);
    const merged = mergeOptions<ZipkinConfigOptions>(fromEnv, overrides);
    return new ZipkinConfig(merged);
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    if (!isHttpUrl(this.endpoint))
      throw new ConfigError("invalid configuration: endpoint must be an http or https URL");
  }

  /** Credential values to redact from error messages and logs (sensitive header values). */
  knownSecrets(): string[] {
    return Object.entries(this.headers)
      .filter(([name, value]) => isSensitiveHeader(name) && value !== "")
      .map(([, value]) => value);
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    return { ...describeCommonConfig(this), endpoint: redactUrl(this.endpoint) };
  }

  toString(): string {
    return `ZipkinConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
