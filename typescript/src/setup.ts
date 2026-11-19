// Service-owned: the Zipkin tracing preset — native Zipkin v2 JSON export through
// ZipkinSpanExporter (default) or OTLP to an OpenTelemetry Collector, W3C + B3 propagation and the
// environment precedence.

import { readFileSync } from "node:fs";
import { credentials as grpcCredentials, Metadata } from "@grpc/grpc-js";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpHttpExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { CompressionAlgorithm } from "@opentelemetry/otlp-exporter-base";
import type { SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ConfigError, type EnvMap, parseDuration } from "./core/index.js";
import { SPANS_PATH, ZipkinConfig, spansEndpoint, type ZipkinConfigOptions } from "./config.js";
import { ZipkinSpanExporter } from "./exporter.js";
import type { ZipkinInternals } from "./reporter.js";
import {
  initTracingWith,
  type InitTracingOptions,
  type ResolvedTracingConfig,
  type SamplerName,
  type SpanExporterFactory,
  type TracingConfig,
  type TracingHandle,
  type TracingProtocol,
} from "./tracing/index.js";

/** Default protocol of the Zipkin preset: Zipkin v2 JSON to `/api/v2/spans`. */
export const ZIPKIN_DEFAULT_PROTOCOL: TracingProtocol = "native";

/**
 * Default propagators of the Zipkin preset: W3C trace context + baggage and B3 single-header
 * (`b3`). Add `b3multi` to also inject the `X-B3-*` headers. Every B3 propagator extracts both
 * B3 forms.
 */
export const ZIPKIN_DEFAULT_PROPAGATORS: readonly string[] = Object.freeze([
  "tracecontext",
  "baggage",
  "b3",
]);

const B3_NAMES: ReadonlySet<string> = new Set(["b3", "b3multi"]);

/**
 * The order in which propagators are composed: B3 (`b3`, `b3multi`) first, every other
 * propagator after it in the given order. On extract, later propagators overwrite earlier ones,
 * so W3C `traceparent` wins when it and B3 disagree. Names are trimmed and lower-cased;
 * duplicates are removed.
 */
export function orderPropagators(names: readonly string[]): string[] {
  const normalized = [...new Set(names.map((n) => n.trim().toLowerCase()))];
  return [
    ...normalized.filter((n) => B3_NAMES.has(n)),
    ...normalized.filter((n) => !B3_NAMES.has(n)),
  ];
}

/** Options of {@link initTracing}. */
export interface ZipkinInitTracingOptions extends InitTracingOptions {
  /**
   * Environment beneath the explicit config (see {@link tracingConfigFromEnv}); default
   * `process.env`, `false` ignores the environment.
   */
  env?: EnvMap | false;
  /**
   * Connection settings of the native exporter (proxy, TLS, retries, hooks, logger, headers,
   * endpoint). Default: `ZipkinConfig.fromEnv()` (or `new ZipkinConfig()` with `env: false`).
   */
  clientConfig?: ZipkinConfigOptions;
  /** Called with every failed native export (the batch is dropped). */
  onExportError?: (error: Error) => void;
  /** Test seams of the native exporter. @internal */
  internals?: ZipkinInternals;
}

/** A running Zipkin tracing setup. */
export interface ZipkinTracingHandle extends TracingHandle {
  /** The exporter endpoint in use. */
  readonly endpoint: string;
}

const PROTOCOLS: readonly string[] = ["grpc", "http/protobuf", "native"];
const SAMPLERS: readonly string[] = [
  "always_on",
  "always_off",
  "traceidratio",
  "parentbased_always_on",
  "parentbased_always_off",
  "parentbased_traceidratio",
];

function hit(env: EnvMap, names: readonly string[]): { name: string; value: string } | undefined {
  for (const name of names) {
    const value = env[name];
    if (value !== undefined && value.trim() !== "") return { name, value: value.trim() };
  }
  return undefined;
}

function parseKeyValues(raw: string, name: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of raw.split(",")) {
    if (part.trim() === "") continue;
    const eq = part.indexOf("=");
    if (eq <= 0)
      throw new ConfigError(
        `invalid value in environment variable ${name}: expected key=value pairs`,
      );
    try {
      out[decodeURIComponent(part.slice(0, eq).trim())] = decodeURIComponent(
        part.slice(eq + 1).trim(),
      );
    } catch {
      throw new ConfigError(`invalid value in environment variable ${name}: bad percent-encoding`);
    }
  }
  return out;
}

function hasPath(url: string): boolean {
  try {
    const path = new URL(url).pathname;
    return path !== "" && path !== "/";
  } catch {
    return true;
  }
}

function definedOnly<T extends object>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as Partial<T>;
}

/**
 * The OTLP endpoint for a protocol: for `http/protobuf`, an endpoint without a path gets
 * `/v1/traces`; any other endpoint is used as-is.
 */
export function resolveOtlpEndpoint(protocol: TracingProtocol, endpoint: string): string {
  return protocol === "http/protobuf" && !hasPath(endpoint)
    ? `${endpoint.replace(/\/+$/, "")}/v1/traces`
    : endpoint;
}

/**
 * Builds a {@link TracingConfig} from the environment with `overrides` on top (each field:
 * explicit > environment > preset default).
 *
 * | Field | Variables |
 * | - | - |
 * | `protocol` | `GROW_ZIPKIN_PROTOCOL` (`native`, `grpc`, `http/protobuf`) |
 * | `endpoint` (native) | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (as-is), `GROW_ZIPKIN_URL` / `ZIPKIN_URL` + `/api/v2/spans` |
 * | `endpoint` (OTLP, a Collector) | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (as-is), `OTEL_EXPORTER_OTLP_ENDPOINT` (HTTP appends `/v1/traces`) |
 * | `batch.exportTimeoutMs` | `OTEL_EXPORTER_ZIPKIN_TIMEOUT` (ms or `10s`) |
 * | `compression` | `GROW_ZIPKIN_COMPRESSION` (`gzip`, `none`) |
 * | `serviceName` | `OTEL_SERVICE_NAME`, else `service.name` in `OTEL_RESOURCE_ATTRIBUTES` |
 * | `resourceAttributes` | `OTEL_RESOURCE_ATTRIBUTES` |
 * | `sampler`, `samplerRatio` | `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` |
 * | `propagators` | `OTEL_PROPAGATORS` |
 *
 * A blank explicit `serviceName` counts as unset. Headers come from code only (no credential
 * variables exist). A present but invalid value
 * throws `ConfigError` naming the variable, never its value.
 */
export function tracingConfigFromEnv(
  overrides: Partial<TracingConfig> = {},
  env: EnvMap = process.env,
): TracingConfig {
  const fromEnv: Partial<TracingConfig> = {};
  const protocolHit = hit(env, ["GROW_ZIPKIN_PROTOCOL"]);
  if (protocolHit !== undefined) {
    const value = protocolHit.value.toLowerCase();
    if (!PROTOCOLS.includes(value))
      throw new ConfigError(
        `invalid value in environment variable ${protocolHit.name}: expected native, grpc or http/protobuf`,
      );
    fromEnv.protocol = value as TracingProtocol;
  }
  const protocol = overrides.protocol ?? fromEnv.protocol ?? ZIPKIN_DEFAULT_PROTOCOL;
  if (protocol === "native") {
    const endpoint = hit(env, ["GROW_ZIPKIN_ENDPOINT", "OTEL_EXPORTER_ZIPKIN_ENDPOINT"]);
    const url = hit(env, ["GROW_ZIPKIN_URL", "ZIPKIN_URL"]);
    if (endpoint !== undefined) fromEnv.endpoint = endpoint.value;
    else if (url !== undefined) fromEnv.endpoint = spansEndpoint(url.value);
  } else {
    const traces = hit(env, ["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"]);
    const base = hit(env, ["OTEL_EXPORTER_OTLP_ENDPOINT"]);
    if (traces !== undefined) fromEnv.endpoint = traces.value;
    else if (base !== undefined) fromEnv.endpoint = resolveOtlpEndpoint(protocol, base.value);
  }
  const timeout = hit(env, ["OTEL_EXPORTER_ZIPKIN_TIMEOUT"]);
  if (timeout !== undefined) {
    const ms = parseDuration(timeout.value);
    if (ms === undefined || ms <= 0)
      throw new ConfigError(
        `invalid value in environment variable ${timeout.name}: expected a duration`,
      );
    fromEnv.batch = { exportTimeoutMs: ms };
  }
  const compression = hit(env, ["GROW_ZIPKIN_COMPRESSION"]);
  if (compression !== undefined) {
    const value = compression.value.toLowerCase();
    if (value !== "gzip" && value !== "none")
      throw new ConfigError(
        `invalid value in environment variable ${compression.name}: expected gzip or none`,
      );
    fromEnv.compression = value;
  }
  const resource = hit(env, ["OTEL_RESOURCE_ATTRIBUTES"]);
  const resourceAttributes =
    resource === undefined ? {} : parseKeyValues(resource.value, resource.name);
  if (resource !== undefined) {
    const { "service.name": _name, ...rest } = resourceAttributes;
    fromEnv.resourceAttributes = rest;
  }
  const serviceName = hit(env, ["OTEL_SERVICE_NAME"])?.value ?? resourceAttributes["service.name"];
  if (serviceName !== undefined) fromEnv.serviceName = serviceName;
  const sampler = hit(env, ["OTEL_TRACES_SAMPLER"]);
  if (sampler !== undefined) {
    const value = sampler.value.toLowerCase();
    if (!SAMPLERS.includes(value))
      throw new ConfigError(
        `invalid value in environment variable ${sampler.name}: unknown sampler`,
      );
    fromEnv.sampler = value as SamplerName;
    const arg = hit(env, ["OTEL_TRACES_SAMPLER_ARG"]);
    if (value.endsWith("traceidratio") && arg !== undefined) {
      const ratio = Number(arg.value);
      if (!Number.isFinite(ratio) || ratio < 0 || ratio > 1)
        throw new ConfigError(
          `invalid value in environment variable ${arg.name}: expected a ratio between 0 and 1`,
        );
      fromEnv.samplerRatio = ratio;
    }
  }
  const propagators = hit(env, ["OTEL_PROPAGATORS"]);
  if (propagators !== undefined)
    fromEnv.propagators = propagators.value
      .split(",")
      .map((p) => p.trim())
      .filter((p) => p !== "");

  const explicit = definedOnly(overrides);
  if (explicit.serviceName?.trim() === "") delete explicit.serviceName;
  const merged: Partial<TracingConfig> = { ...fromEnv, ...explicit };
  if (fromEnv.batch !== undefined && explicit.batch !== undefined)
    merged.batch = { ...fromEnv.batch, ...definedOnly(explicit.batch) };
  return { ...merged, serviceName: merged.serviceName ?? "" };
}

function readPem(path: string | undefined): Buffer | undefined {
  if (path === undefined) return undefined;
  try {
    return readFileSync(path);
  } catch {
    throw new ConfigError("tracing: cannot read a TLS file (check the configured path)");
  }
}

function otlpExporter(config: ResolvedTracingConfig): SpanExporter {
  if (config.endpoint === undefined || config.endpoint.replace(/\/+$/, "").endsWith(SPANS_PATH))
    throw new ConfigError(
      "tracing: protocol grpc/http/protobuf needs the endpoint of an OpenTelemetry Collector " +
        "with a zipkin exporter (stock Zipkin does not accept OTLP)",
    );
  const url = resolveOtlpEndpoint(config.protocol, config.endpoint);
  const compression =
    config.compression === "gzip" ? CompressionAlgorithm.GZIP : CompressionAlgorithm.NONE;
  const ca = readPem(config.tls.caCertPath) ?? config.tls.caCertPem;
  const cert = readPem(config.tls.clientCertPath);
  const key = readPem(config.tls.clientKeyPath);
  const timeoutMillis = config.batch.exportTimeoutMs;
  if (config.protocol === "grpc") {
    const metadata = new Metadata();
    for (const [k, v] of Object.entries(config.headers)) metadata.set(k.toLowerCase(), v);
    const secure =
      url.startsWith("https:") ||
      ca !== undefined ||
      cert !== undefined ||
      config.tls.insecureSkipVerify === true;
    const credentials = secure
      ? grpcCredentials.createSsl(
          ca === undefined ? null : Buffer.from(ca),
          key ?? null,
          cert ?? null,
          config.tls.insecureSkipVerify === true ? { checkServerIdentity: () => undefined } : {},
        )
      : grpcCredentials.createInsecure();
    return new OtlpGrpcExporter({ url, metadata, compression, timeoutMillis, credentials });
  }
  const agent: Record<string, unknown> = {};
  if (ca !== undefined) agent.ca = ca;
  if (cert !== undefined) agent.cert = cert;
  if (key !== undefined) agent.key = key;
  if (config.tls.insecureSkipVerify === true) agent.rejectUnauthorized = false;
  return new OtlpHttpExporter({
    url,
    headers: { ...config.headers },
    compression,
    timeoutMillis,
    ...(Object.keys(agent).length > 0 ? { httpAgentOptions: agent } : {}),
  });
}

/** Options of {@link createZipkinExporterFactory}. */
export interface ZipkinExporterFactoryOptions {
  /** Connection settings beneath the tracing config. Default `new ZipkinConfig()`. */
  clientConfig?: ZipkinConfigOptions;
  /** Called with every failed native export. */
  onExportError?: (error: Error) => void;
  /** Test seams. @internal */
  internals?: ZipkinInternals;
}

/**
 * Builds the `exporterFactory` of `initTracingWith`:
 *
 * - `native` (default) → {@link ZipkinSpanExporter} to `config.endpoint` (else the client
 *   config's endpoint). Headers = client headers + tracing headers; TLS = client TLS + tracing
 *   TLS; request and total timeout = `batch.exportTimeoutMs`; `compression` (default gzip);
 *   proxy, retries, hooks and logger from the client config.
 * - `http/protobuf` / `grpc` → the official OTLP exporter to an OpenTelemetry Collector
 *   (`config.endpoint` is required; the Collector's `zipkin` exporter forwards to Zipkin).
 */
export function createZipkinExporterFactory(
  options: ZipkinExporterFactoryOptions = {},
): SpanExporterFactory {
  return (config: ResolvedTracingConfig): SpanExporter => {
    if (config.protocol !== "native") return otlpExporter(config);
    const base = ZipkinConfig.from(options.clientConfig ?? {});
    const exporterConfig = new ZipkinConfig({
      url: base.url,
      endpoint: config.endpoint ?? base.endpoint,
      connectTimeoutMs: base.connectTimeoutMs,
      requestTimeoutMs: config.batch.exportTimeoutMs,
      totalTimeoutMs: config.batch.exportTimeoutMs,
      retry: base.retry,
      tls: { ...base.tls, ...config.tls },
      headers: { ...base.headers, ...config.headers },
      ...(base.proxy === undefined ? {} : { proxy: base.proxy }),
      ...(base.userAgentSuffix === undefined ? {} : { userAgentSuffix: base.userAgentSuffix }),
      ...(base.hooks === undefined ? {} : { hooks: base.hooks }),
      ...(base.logger === undefined ? {} : { logger: base.logger }),
    });
    return new ZipkinSpanExporter(
      {
        config: exporterConfig,
        compression: config.compression,
        maxBatchSize: config.batch.maxExportBatchSize,
        ...(options.onExportError === undefined ? {} : { onError: options.onExportError }),
      },
      options.internals,
    );
  };
}

/**
 * Sets up OpenTelemetry tracing for Zipkin (SPEC §5). Defaults: protocol `native` (Zipkin v2
 * JSON via {@link ZipkinSpanExporter} to `http://127.0.0.1:9411/api/v2/spans`), propagators
 * `tracecontext, baggage, b3` composed B3-first so W3C wins on conflict
 * ({@link orderPropagators}). The environment fills every field the config leaves unset (see
 * {@link tracingConfigFromEnv} and `ZipkinConfig.fromEnv`); pass `{ env: false }` to ignore it.
 */
export function initTracing(
  config: TracingConfig,
  options: ZipkinInitTracingOptions = {},
): ZipkinTracingHandle {
  const { env, clientConfig, onExportError, internals, ...initOptions } = options;
  const effective =
    env === false ? { ...config } : tracingConfigFromEnv(config, env ?? process.env);
  const protocol = effective.protocol ?? ZIPKIN_DEFAULT_PROTOCOL;
  const base =
    clientConfig !== undefined
      ? ZipkinConfig.from(clientConfig)
      : env === false
        ? new ZipkinConfig()
        : ZipkinConfig.fromEnv({}, env ?? process.env);
  const endpoint = effective.endpoint ?? (protocol === "native" ? base.endpoint : undefined);
  const propagators = orderPropagators(effective.propagators ?? ZIPKIN_DEFAULT_PROPAGATORS);
  const handle = initTracingWith(
    {
      ...effective,
      protocol,
      propagators,
      ...(endpoint === undefined ? {} : { endpoint }),
    },
    createZipkinExporterFactory({
      clientConfig: base,
      ...(onExportError === undefined ? {} : { onExportError }),
      ...(internals === undefined ? {} : { internals }),
    }),
    initOptions,
  );
  return { ...handle, endpoint: handle.config.endpoint ?? "" };
}
