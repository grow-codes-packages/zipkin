// Service code (Zipkin): the tracing preset — native Zipkin v2 JSON export through
// ZipkinSpanExporter (default) or OTLP to an OpenTelemetry Collector, W3C + B3 propagation and
// the environment precedence.
//! `init_tracing` for Zipkin: protocol `native` (Zipkin v2 JSON to
//! `http://127.0.0.1:9411/api/v2/spans` through [`ZipkinSpanExporter`]) by default, OTLP to an
//! OpenTelemetry Collector as an option, propagators `tracecontext, baggage, b3`.

use std::fmt;
use std::pin::Pin;
use std::time::Duration;

use opentelemetry_sdk::error::OTelSdkResult;
use opentelemetry_sdk::trace::{SpanData, SpanExporter};
use opentelemetry_sdk::Resource;

use crate::config::{
    spans_endpoint, ZipkinConfig, ENV_OTEL_ZIPKIN_ENDPOINT, ENV_ZIPKIN_URL, SPANS_PATH,
};
use crate::core::{parse_duration, EnvReader, Error, Result, TlsConfig};
use crate::exporter::{ExportErrorHandler, ZipkinSpanExporter, ZipkinSpanExporterOptions};
use crate::tracing::{
    init_tracing_with, otlp_exporter, Compression, PropagatorKind, Protocol, SamplerKind,
    TraceBatchConfig, TracingConfig, TracingHandle,
};

/// Default protocol of the Zipkin preset: Zipkin v2 JSON to `/api/v2/spans`.
pub const DEFAULT_TRACING_PROTOCOL: Protocol = Protocol::Native;

/// Default propagators of the Zipkin preset: W3C trace context + baggage and B3 single-header
/// (`b3`). Add [`PropagatorKind::B3Multi`] to also inject the `X-B3-*` headers; every B3
/// propagator extracts both B3 forms.
pub fn default_propagators() -> Vec<PropagatorKind> {
    vec![
        PropagatorKind::TraceContext,
        PropagatorKind::Baggage,
        PropagatorKind::B3,
    ]
}

/// The order in which propagators are composed: B3 (`b3`, `b3multi`) first, every other
/// propagator after it in the given order, duplicates removed. On extract, later propagators
/// overwrite earlier ones, so W3C `traceparent` wins when it and B3 disagree.
pub fn order_propagators(kinds: &[PropagatorKind]) -> Vec<PropagatorKind> {
    let mut unique: Vec<PropagatorKind> = Vec::new();
    for k in kinds {
        if !unique.contains(k) {
            unique.push(k.clone());
        }
    }
    let is_b3 = |k: &PropagatorKind| matches!(k, PropagatorKind::B3 | PropagatorKind::B3Multi);
    let (mut b3, rest): (Vec<_>, Vec<_>) = unique.into_iter().partition(is_b3);
    b3.extend(rest);
    b3
}

/// Options of [`init_tracing_with_options`].
#[derive(Clone, Default)]
pub struct ZipkinTracingOptions {
    env: Option<EnvReader>,
    client_config: Option<ZipkinConfig>,
    protocol: Option<Protocol>,
    on_export_error: Option<ExportErrorHandler>,
}

impl fmt::Debug for ZipkinTracingOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ZipkinTracingOptions")
            .field("env", &self.env.as_ref().map(|_| "EnvReader"))
            .field("client_config", &self.client_config)
            .field("protocol", &self.protocol)
            .field(
                "on_export_error",
                &self.on_export_error.as_ref().map(|_| "Fn"),
            )
            .finish()
    }
}

impl ZipkinTracingOptions {
    /// Default options: the process environment fills unset fields.
    pub fn new() -> Self {
        Self::default()
    }
    /// Reads unset fields from `env` instead of the process environment.
    pub fn env(mut self, env: EnvReader) -> Self {
        self.env = Some(env);
        self
    }
    /// Ignores the environment.
    pub fn without_env(mut self) -> Self {
        self.env = Some(EnvReader::from_map(Vec::<(String, String)>::new()));
        self
    }
    /// Connection settings of the native exporter (proxy, TLS, retries, hooks, headers,
    /// endpoint). Default: [`ZipkinConfig`] from the same environment.
    pub fn client_config(mut self, config: ZipkinConfig) -> Self {
        self.client_config = Some(config);
        self
    }
    /// Forces the protocol (wins over `TracingConfig::protocol` and the environment). Use it to
    /// select OTLP `http/protobuf`, which is also the template default of
    /// [`TracingConfig::new`] and therefore counts as unset there.
    pub fn protocol(mut self, protocol: Protocol) -> Self {
        self.protocol = Some(protocol);
        self
    }
    pub(crate) fn env_reader(&self) -> EnvReader {
        self.env.clone().unwrap_or_default()
    }
    pub(crate) fn protocol_override(&self) -> Option<Protocol> {
        self.protocol
    }
    /// Called with every failed native export (the batch is dropped).
    pub fn on_export_error(mut self, handler: ExportErrorHandler) -> Self {
        self.on_export_error = Some(handler);
        self
    }
}

fn otel(env: &EnvReader, name: &str) -> Option<(String, String)> {
    // The empty suffix maps to the bare prefix, which is never set.
    env.lookup("", &[name])
        .map(|(var, value)| (var, value.trim().to_owned()))
        .filter(|(_, v)| !v.is_empty())
}

fn parse_key_values(raw: &str, variable: &str) -> Result<Vec<(String, String)>> {
    let bad = || Error::config(format!("{variable} must be a list of key=value pairs"));
    let decode = |s: &str| {
        url::form_urlencoded::parse(format!("x={}", s.trim()).as_bytes())
            .next()
            .map(|(_, v)| v.into_owned())
            .unwrap_or_default()
    };
    let mut out = Vec::new();
    for part in raw.split(',').filter(|p| !p.trim().is_empty()) {
        let (k, v) = part.split_once('=').ok_or_else(bad)?;
        let key = decode(k);
        if key.is_empty() {
            return Err(bad());
        }
        out.push((key, decode(v)));
    }
    Ok(out)
}

fn otlp_endpoint(protocol: Protocol, base: &str) -> String {
    let has_path = url::Url::parse(base)
        .map(|u| u.path() != "/" && !u.path().is_empty())
        .unwrap_or(true);
    if protocol == Protocol::HttpProtobuf && !has_path {
        format!("{}/v1/traces", base.trim_end_matches('/'))
    } else {
        base.to_owned()
    }
}

/// Fills the unset fields of `config` from the process environment and applies the Zipkin
/// defaults; see [`tracing_config_from_env_with`].
pub fn tracing_config_from_env(config: TracingConfig) -> Result<TracingConfig> {
    tracing_config_from_env_with(config, &EnvReader::new())
}

/// Fills the unset fields of `config` from `env` and applies the Zipkin defaults (explicit >
/// environment > default). A field still at its [`TracingConfig::new`] default counts as unset:
///
/// | Field (unset when) | Variables, then default |
/// | - | - |
/// | `protocol` (`http/protobuf`) | `GROW_ZIPKIN_PROTOCOL` (`native`, `grpc`, `http/protobuf`), then `native` |
/// | `endpoint` (`None`), native | `GROW_ZIPKIN_ENDPOINT`, `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (as-is), `GROW_ZIPKIN_URL` / `ZIPKIN_URL` + `/api/v2/spans` |
/// | `endpoint` (`None`), OTLP (a Collector) | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (as-is), `OTEL_EXPORTER_OTLP_ENDPOINT` (HTTP without a path appends `/v1/traces`) |
/// | `batch.export_timeout` (30 s) | `OTEL_EXPORTER_ZIPKIN_TIMEOUT` (ms or `10s`) |
/// | `compression` (gzip) | `GROW_ZIPKIN_COMPRESSION` (`gzip`, `none`) |
/// | `service_name` (blank) | `OTEL_SERVICE_NAME`, else `service.name` in `OTEL_RESOURCE_ATTRIBUTES` |
/// | `resource_attributes` (per key) | `OTEL_RESOURCE_ATTRIBUTES` |
/// | `sampler` (`parentbased_always_on`, ratio 1) | `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` |
/// | `propagators` (`tracecontext, baggage`) | `OTEL_PROPAGATORS`, then [`default_propagators`] |
///
/// Propagators are then ordered with [`order_propagators`] (W3C wins on conflict). Headers come
/// from code only (no credential variables exist). A present but invalid value is a `CONFIG`
/// error naming the variable, never its value.
pub fn tracing_config_from_env_with(
    config: TracingConfig,
    env: &EnvReader,
) -> Result<TracingConfig> {
    resolve(config, env, None)
}

fn resolve(
    config: TracingConfig,
    env: &EnvReader,
    protocol: Option<Protocol>,
) -> Result<TracingConfig> {
    let template = TracingConfig::new("");
    let mut c = config;
    c.protocol = resolve_protocol(c.protocol, env, protocol)?;
    if c.endpoint.is_none() {
        c.endpoint = if c.protocol == Protocol::Native {
            env.string("ENDPOINT", &[ENV_OTEL_ZIPKIN_ENDPOINT])
                .map(|v| v.trim().to_owned())
                .or_else(|| {
                    env.string("URL", &[ENV_ZIPKIN_URL])
                        .map(|v| spans_endpoint(v.trim()))
                })
        } else {
            otel(env, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
                .map(|(_, v)| v)
                .or_else(|| {
                    otel(env, "OTEL_EXPORTER_OTLP_ENDPOINT")
                        .map(|(_, v)| otlp_endpoint(c.protocol, &v))
                })
        };
    }
    if c.batch.export_timeout == TraceBatchConfig::default().export_timeout {
        if let Some((var, v)) = otel(env, "OTEL_EXPORTER_ZIPKIN_TIMEOUT") {
            c.batch.export_timeout = parse_duration(&v)
                .filter(|d| !d.is_zero())
                .ok_or_else(|| Error::config(format!("{var} must be a duration")))?;
        }
    }
    if c.compression == template.compression {
        if let Some((var, v)) = env.lookup("COMPRESSION", &[]) {
            c.compression = match v.trim().to_ascii_lowercase().as_str() {
                "gzip" => Compression::Gzip,
                "none" => Compression::None,
                _ => return Err(Error::config(format!("{var} must be gzip or none"))),
            };
        }
    }
    let mut resource_service = None;
    if let Some((var, raw)) = otel(env, "OTEL_RESOURCE_ATTRIBUTES") {
        for (k, v) in parse_key_values(&raw, &var)? {
            if k == "service.name" {
                resource_service = Some(v);
            } else {
                c.resource_attributes.entry(k).or_insert(v.into());
            }
        }
    }
    if c.service_name.trim().is_empty() {
        c.service_name = otel(env, "OTEL_SERVICE_NAME")
            .map(|(_, v)| v)
            .or(resource_service)
            .unwrap_or_default();
    }
    if c.sampler == template.sampler && c.sampler_ratio == template.sampler_ratio {
        if let Some((var, v)) = otel(env, "OTEL_TRACES_SAMPLER") {
            let kind: SamplerKind = v
                .parse()
                .map_err(|_| Error::config(format!("{var} names an unknown sampler")))?;
            let mut ratio = 1.0;
            if matches!(
                kind,
                SamplerKind::TraceIdRatio | SamplerKind::ParentBasedTraceIdRatio
            ) {
                if let Some((arg_var, arg)) = otel(env, "OTEL_TRACES_SAMPLER_ARG") {
                    ratio = arg
                        .parse::<f64>()
                        .ok()
                        .filter(|r| (0.0..=1.0).contains(r))
                        .ok_or_else(|| {
                            Error::config(format!("{arg_var} must be a ratio between 0 and 1"))
                        })?;
                }
            }
            c.sampler = kind;
            c.sampler_ratio = ratio;
        }
    }
    if c.propagators == template.propagators || c.propagators.is_empty() {
        c.propagators = match otel(env, "OTEL_PROPAGATORS") {
            Some((_, v)) => v
                .split(',')
                .filter(|p| !p.trim().is_empty())
                .map(str::parse)
                .collect::<Result<Vec<PropagatorKind>>>()?,
            None => default_propagators(),
        };
    }
    c.propagators = order_propagators(&c.propagators);
    Ok(c)
}

/// The protocol `init_tracing` uses: the override, else an explicit `grpc`/`native`, else
/// `GROW_ZIPKIN_PROTOCOL`, else `native`.
pub(crate) fn resolve_protocol(
    configured: Protocol,
    env: &EnvReader,
    protocol: Option<Protocol>,
) -> Result<Protocol> {
    Ok(match protocol {
        Some(p) => p,
        None if configured != Protocol::HttpProtobuf => configured,
        None => match env.lookup("PROTOCOL", &[]) {
            Some((var, v)) => match v.trim().to_ascii_lowercase().as_str() {
                "native" => Protocol::Native,
                "grpc" => Protocol::Grpc,
                "http/protobuf" => Protocol::HttpProtobuf,
                _ => {
                    return Err(Error::config(format!(
                        "{var} must be native, grpc or http/protobuf"
                    )))
                }
            },
            None => DEFAULT_TRACING_PROTOCOL,
        },
    })
}

type ExportFuture<'a> = Pin<Box<dyn std::future::Future<Output = OTelSdkResult> + Send + 'a>>;

/// The exporter built by [`zipkin_exporter_factory`]: the native Zipkin exporter or the
/// official OTLP exporter (to an OpenTelemetry Collector).
#[derive(Debug)]
pub enum ZipkinExporter {
    /// Zipkin v2 JSON to `/api/v2/spans`.
    Native(ZipkinSpanExporter),
    /// OTLP (`grpc` or `http/protobuf`) to a Collector with a `zipkin` exporter.
    Otlp(opentelemetry_otlp::SpanExporter),
}

impl SpanExporter for ZipkinExporter {
    fn export(
        &self,
        batch: Vec<SpanData>,
    ) -> impl std::future::Future<Output = OTelSdkResult> + Send {
        let fut: ExportFuture<'_> = match self {
            ZipkinExporter::Native(e) => Box::pin(e.export(batch)),
            ZipkinExporter::Otlp(e) => Box::pin(e.export(batch)),
        };
        fut
    }

    fn shutdown_with_timeout(&mut self, timeout: Duration) -> OTelSdkResult {
        match self {
            ZipkinExporter::Native(e) => e.shutdown_with_timeout(timeout),
            ZipkinExporter::Otlp(e) => e.shutdown_with_timeout(timeout),
        }
    }

    fn force_flush(&mut self) -> OTelSdkResult {
        match self {
            ZipkinExporter::Native(e) => e.force_flush(),
            ZipkinExporter::Otlp(e) => e.force_flush(),
        }
    }

    fn set_resource(&mut self, resource: &Resource) {
        match self {
            ZipkinExporter::Native(e) => e.set_resource(resource),
            ZipkinExporter::Otlp(e) => e.set_resource(resource),
        }
    }
}

fn merge_tls(base: &TlsConfig, over: Option<&TlsConfig>) -> TlsConfig {
    let Some(o) = over else {
        return base.clone();
    };
    TlsConfig {
        ca_cert_path: o.ca_cert_path.clone().or_else(|| base.ca_cert_path.clone()),
        ca_cert_pem: o.ca_cert_pem.clone().or_else(|| base.ca_cert_pem.clone()),
        client_cert_path: o
            .client_cert_path
            .clone()
            .or_else(|| base.client_cert_path.clone()),
        client_key_path: o
            .client_key_path
            .clone()
            .or_else(|| base.client_key_path.clone()),
        insecure_skip_verify: o.insecure_skip_verify || base.insecure_skip_verify,
    }
}

/// Builds the `exporter_factory` of [`init_tracing_with`]:
///
/// - `native` → [`ZipkinSpanExporter`] to `config.endpoint` (else the client config's
///   endpoint). Headers = client headers + tracing headers; TLS = client TLS + tracing TLS;
///   request and total timeout = `batch.export_timeout`; `compression`; batch size =
///   `batch.max_export_batch_size`; proxy, retries, hooks and User-Agent suffix from the client
///   config.
/// - `grpc` / `http/protobuf` → the official OTLP exporter to an OpenTelemetry Collector
///   (`config.endpoint` is required and must not be Zipkin's `/api/v2/spans`; the Collector's
///   `zipkin` exporter forwards to Zipkin).
pub fn zipkin_exporter_factory(
    client_config: ZipkinConfig,
    on_export_error: Option<ExportErrorHandler>,
) -> impl FnOnce(&TracingConfig) -> Result<ZipkinExporter> {
    move |config: &TracingConfig| {
        if config.protocol != Protocol::Native {
            let ok = config
                .endpoint
                .as_deref()
                .is_some_and(|e| !e.trim_end_matches('/').ends_with(SPANS_PATH));
            if !ok {
                return Err(Error::config(
                    "tracing: protocol grpc/http/protobuf needs the endpoint of an OpenTelemetry \
                     Collector with a zipkin exporter (stock Zipkin does not accept OTLP)",
                ));
            }
            return otlp_exporter(config).map(ZipkinExporter::Otlp);
        }
        let base = client_config.common();
        let tls = merge_tls(base.tls(), config.tls.as_ref());
        let timeout = config.batch.export_timeout;
        let mut b = ZipkinConfig::builder()
            .url(base.url().as_str())
            .endpoint(
                config
                    .endpoint
                    .clone()
                    .unwrap_or_else(|| client_config.endpoint().to_owned()),
            )
            .connect_timeout(base.connect_timeout())
            .request_timeout(timeout)
            .total_timeout(timeout)
            .retry(base.retry().clone())
            .insecure_skip_verify(tls.insecure_skip_verify)
            .tls(tls);
        for (k, v) in base.headers() {
            b = b.header(k, v);
        }
        for (k, v) in &config.headers {
            b = b.header(k, v);
        }
        if let Some(p) = base.proxy() {
            b = b.proxy(p);
        }
        if let Some(s) = base.user_agent_suffix() {
            b = b.user_agent_suffix(s);
        }
        if let Some(h) = base.hooks() {
            b = b.hooks(h.clone());
        }
        let exporter = ZipkinSpanExporter::new(
            b.build()?,
            ZipkinSpanExporterOptions {
                compression: config.compression,
                max_batch_size: config.batch.max_export_batch_size,
                on_error: on_export_error,
            },
        )?;
        Ok(ZipkinExporter::Native(exporter))
    }
}

/// Sets up OpenTelemetry tracing for Zipkin (SPEC §5) with the process environment; see
/// [`init_tracing_with_options`]. Must be called inside a tokio runtime.
///
/// ```no_run
/// # async fn demo() -> grow_zipkin::Result<()> {
/// use grow_zipkin::tracing::{traced, TracingConfig};
///
/// let tracing = grow_zipkin::init_tracing(TracingConfig::new("checkout"))?;
/// traced("charge-card", async { Ok::<_, std::io::Error>(()) }).await.ok();
/// tracing.shutdown().await?;
/// # Ok(()) }
/// ```
pub fn init_tracing(config: TracingConfig) -> Result<TracingHandle> {
    init_tracing_with_options(config, ZipkinTracingOptions::default())
}

/// Sets up OpenTelemetry tracing for Zipkin. Defaults: protocol `native` (Zipkin v2 JSON via
/// [`ZipkinSpanExporter`] to `http://127.0.0.1:9411/api/v2/spans`), propagators
/// `tracecontext, baggage, b3` composed B3-first so W3C wins on conflict. The environment fills
/// every unset field (see [`tracing_config_from_env_with`] and [`ZipkinConfig::from_env`]).
pub fn init_tracing_with_options(
    config: TracingConfig,
    options: ZipkinTracingOptions,
) -> Result<TracingHandle> {
    let env = options.env.unwrap_or_default();
    let mut effective = resolve(config, &env, options.protocol)?;
    let base = match options.client_config {
        Some(c) => c,
        None => ZipkinConfig::builder().build_with_env(&env)?,
    };
    if effective.protocol == Protocol::Native && effective.endpoint.is_none() {
        effective.endpoint = Some(base.endpoint().to_owned());
    }
    init_tracing_with(
        effective,
        zipkin_exporter_factory(base, options.on_export_error),
    )
}
