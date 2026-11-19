// Service code (Zipkin): ZipkinSpanExporter — an OpenTelemetry SpanExporter that sends Zipkin v2
// JSON through ZipkinReporter (no dependency on the discontinued `opentelemetry-zipkin` crate).
//! [`ZipkinSpanExporter`] and the span mapping [`to_zipkin_span`].

use std::collections::BTreeMap;
use std::fmt;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use opentelemetry::trace::{SpanId, SpanKind, Status};
use opentelemetry::{Array, Key, KeyValue, Value as OtelValue};
use opentelemetry_sdk::error::{OTelSdkError, OTelSdkResult};
use opentelemetry_sdk::trace::{SpanData, SpanExporter};
use opentelemetry_sdk::Resource;
use serde_json::Value;

use crate::config::ZipkinConfig;
use crate::core::{Error, Result};
use crate::model::{ZipkinAnnotation, ZipkinEndpoint, ZipkinKind, ZipkinSpan};
use crate::reporter::{ZipkinReporter, ZipkinReporterOptions, DEFAULT_MAX_BATCH_SIZE};
use crate::tracing::Compression;

/// Service name used when the resource has no `service.name`.
pub const UNKNOWN_SERVICE: &str = "unknown_service";

/// Span attributes that name the remote service of a CLIENT/PRODUCER span, by precedence.
pub const REMOTE_SERVICE_KEYS: &[&str] = &[
    "peer.service",
    "server.address",
    "net.peer.name",
    "network.peer.address",
];

/// Callback for failed exports (see [`ZipkinSpanExporterOptions::on_error`]).
pub type ExportErrorHandler = Arc<dyn Fn(&Error) + Send + Sync>;

fn json_number(f: f64) -> Value {
    if f.is_finite() && f.fract() == 0.0 && f.abs() < 9_007_199_254_740_992.0 {
        Value::from(f as i64)
    } else {
        serde_json::Number::from_f64(f).map_or(Value::Null, Value::Number)
    }
}

fn attribute_json(value: &OtelValue) -> Value {
    match value {
        OtelValue::Bool(b) => Value::Bool(*b),
        OtelValue::I64(i) => Value::from(*i),
        OtelValue::F64(f) => json_number(*f),
        OtelValue::String(s) => Value::String(s.as_str().to_owned()),
        OtelValue::Array(Array::Bool(a)) => a.iter().map(|b| Value::Bool(*b)).collect(),
        OtelValue::Array(Array::I64(a)) => a.iter().map(|i| Value::from(*i)).collect(),
        OtelValue::Array(Array::F64(a)) => a.iter().map(|f| json_number(*f)).collect(),
        OtelValue::Array(Array::String(a)) => a
            .iter()
            .map(|s| Value::String(s.as_str().to_owned()))
            .collect(),
        other => Value::String(other.to_string()),
    }
}

/// Converts an OpenTelemetry attribute value to a Zipkin tag: strings as-is, numbers and
/// booleans as text (`7`, `1.5`, `2`, `true`), arrays as JSON (`["a","b"]`, `[1,2]`).
pub fn attribute_to_tag(value: &OtelValue) -> String {
    match value {
        OtelValue::String(s) => s.as_str().to_owned(),
        OtelValue::Bool(b) => b.to_string(),
        OtelValue::I64(i) => i.to_string(),
        OtelValue::F64(f) => json_number(*f).to_string(),
        other => attribute_json(other).to_string(),
    }
}

fn micros(t: SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as u64)
        .unwrap_or(0)
}

fn duration_micros(start: SystemTime, end: SystemTime) -> u64 {
    let nanos = end
        .duration_since(start)
        .unwrap_or(Duration::ZERO)
        .as_nanos();
    (nanos.saturating_add(500) / 1000).max(1) as u64
}

fn remote_service(attributes: &[KeyValue]) -> Option<String> {
    REMOTE_SERVICE_KEYS.iter().find_map(|key| {
        attributes.iter().find_map(|kv| match &kv.value {
            OtelValue::String(s) if kv.key.as_str() == *key && !s.as_str().is_empty() => {
                Some(s.as_str().to_owned())
            }
            _ => None,
        })
    })
}

/// Maps a finished OpenTelemetry span to a Zipkin v2 span (identical in every language of this
/// SDK; based on the OpenTelemetry Zipkin exporter specification):
///
/// | Zipkin | OpenTelemetry |
/// | - | - |
/// | `traceId`, `id` | span context ids (lower-case hex, 32 / 16) |
/// | `parentId` | parent span id when valid, else omitted |
/// | `kind` | SERVER/CLIENT/PRODUCER/CONSUMER 1:1; INTERNAL → omitted |
/// | `name` | span name (the server lower-cases it) |
/// | `timestamp` | start time, epoch µs (truncated) |
/// | `duration` | end − start in µs, rounded, at least 1 |
/// | `localEndpoint.serviceName` | resource `service.name` (`service_name`), else `unknown_service` |
/// | `remoteEndpoint` | CLIENT/PRODUCER only: `{serviceName}` = first non-empty string of `peer.service`, `server.address`, `net.peer.name`, `network.peer.address`; omitted when none is set |
/// | `tags` | span attributes ([`attribute_to_tag`]); then `otel.scope.name` / `otel.scope.version`; `otel.dropped_attributes_count` / `otel.dropped_events_count` when > 0; status ERROR → `otel.status_code=ERROR` + `error=<description or "">`, OK → `otel.status_code=OK` (status tags win). Resource attributes are not copied |
/// | `annotations` | events: `timestamp` = event time µs, `value` = event name, or the JSON text `{"<name>":{…attributes}}` (attribute values keep their JSON type) when the event has attributes |
/// | `debug`, `shared` | never set |
///
/// Links, trace state and resource attributes other than `service.name` are not mapped.
pub fn to_zipkin_span(span: &SpanData, service_name: &str) -> ZipkinSpan {
    let ctx = &span.span_context;
    let kind = match span.span_kind {
        SpanKind::Server => Some(ZipkinKind::Server),
        SpanKind::Client => Some(ZipkinKind::Client),
        SpanKind::Producer => Some(ZipkinKind::Producer),
        SpanKind::Consumer => Some(ZipkinKind::Consumer),
        SpanKind::Internal => None,
    };
    let remote_endpoint = match kind {
        Some(ZipkinKind::Client | ZipkinKind::Producer) => {
            remote_service(&span.attributes).map(ZipkinEndpoint::service)
        }
        _ => None,
    };
    let annotations = span
        .events
        .iter()
        .map(|e| {
            let value = if e.attributes.is_empty() {
                e.name.to_string()
            } else {
                let attrs: serde_json::Map<String, Value> = e
                    .attributes
                    .iter()
                    .map(|kv| (kv.key.to_string(), attribute_json(&kv.value)))
                    .collect();
                let mut outer = serde_json::Map::new();
                outer.insert(e.name.to_string(), Value::Object(attrs));
                Value::Object(outer).to_string()
            };
            ZipkinAnnotation {
                timestamp: micros(e.timestamp),
                value,
            }
        })
        .collect();
    let mut tags: BTreeMap<String, String> = span
        .attributes
        .iter()
        .map(|kv| (kv.key.to_string(), attribute_to_tag(&kv.value)))
        .collect();
    let scope = &span.instrumentation_scope;
    if !scope.name().is_empty() {
        tags.insert("otel.scope.name".into(), scope.name().to_owned());
    }
    if let Some(v) = scope.version().filter(|v| !v.is_empty()) {
        tags.insert("otel.scope.version".into(), v.to_owned());
    }
    if span.dropped_attributes_count > 0 {
        tags.insert(
            "otel.dropped_attributes_count".into(),
            span.dropped_attributes_count.to_string(),
        );
    }
    if span.events.dropped_count > 0 {
        tags.insert(
            "otel.dropped_events_count".into(),
            span.events.dropped_count.to_string(),
        );
    }
    match &span.status {
        Status::Error { description } => {
            tags.insert("otel.status_code".into(), "ERROR".into());
            tags.insert("error".into(), description.to_string());
        }
        Status::Ok => {
            tags.insert("otel.status_code".into(), "OK".into());
        }
        Status::Unset => {}
    }
    ZipkinSpan {
        trace_id: ctx.trace_id().to_string(),
        parent_id: (span.parent_span_id != SpanId::INVALID)
            .then(|| span.parent_span_id.to_string()),
        id: ctx.span_id().to_string(),
        kind,
        name: Some(span.name.to_string()),
        timestamp: Some(micros(span.start_time)),
        duration: Some(duration_micros(span.start_time, span.end_time)),
        local_endpoint: Some(ZipkinEndpoint::service(if service_name.is_empty() {
            UNKNOWN_SERVICE
        } else {
            service_name
        })),
        remote_endpoint,
        annotations,
        tags,
        debug: false,
        shared: false,
    }
}

/// Options of [`ZipkinSpanExporter::new`].
#[derive(Clone)]
pub struct ZipkinSpanExporterOptions {
    /// Request body compression of the owned reporter. Default gzip.
    pub compression: Compression,
    /// Spans per POST of the owned reporter. Default 512.
    pub max_batch_size: usize,
    /// Called with every failed export (the batch is dropped). A panicking handler is caught.
    pub on_error: Option<ExportErrorHandler>,
}

impl Default for ZipkinSpanExporterOptions {
    fn default() -> Self {
        ZipkinSpanExporterOptions {
            compression: Compression::Gzip,
            max_batch_size: DEFAULT_MAX_BATCH_SIZE,
            on_error: None,
        }
    }
}

impl fmt::Debug for ZipkinSpanExporterOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ZipkinSpanExporterOptions")
            .field("compression", &self.compression)
            .field("max_batch_size", &self.max_batch_size)
            .field("on_error", &self.on_error.as_ref().map(|_| "Fn"))
            .finish()
    }
}

/// OpenTelemetry [`SpanExporter`] for Zipkin: maps each span with [`to_zipkin_span`] and sends
/// the batch with a [`ZipkinReporter`] (`POST /api/v2/spans`, JSON v2, gzip by default, retries
/// on 5xx/429/network errors). A failed batch is reported to the span processor and dropped.
pub struct ZipkinSpanExporter {
    reporter: ZipkinReporter,
    owned: bool,
    service_name: String,
    on_error: Option<ExportErrorHandler>,
    shutdown: Arc<AtomicBool>,
}

impl fmt::Debug for ZipkinSpanExporter {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ZipkinSpanExporter")
            .field(
                "endpoint",
                &crate::core::redact::redact_url(self.reporter.endpoint()),
            )
            .field("service_name", &self.service_name)
            .field("shutdown", &self.shutdown.load(Ordering::SeqCst))
            .finish()
    }
}

impl ZipkinSpanExporter {
    /// An exporter with its own reporter (and connections) for `config.endpoint()`.
    pub fn new(config: ZipkinConfig, options: ZipkinSpanExporterOptions) -> Result<Self> {
        let reporter = ZipkinReporter::new(
            config,
            ZipkinReporterOptions::default()
                .compression(options.compression)
                .max_batch_size(options.max_batch_size),
        )?;
        let mut exporter = Self::with_reporter(reporter);
        exporter.owned = true;
        exporter.on_error = options.on_error;
        Ok(exporter)
    }

    /// An exporter with its own reporter configured from the environment
    /// ([`ZipkinConfig::from_env`]).
    pub fn from_env() -> Result<Self> {
        Self::new(
            ZipkinConfig::from_env()?,
            ZipkinSpanExporterOptions::default(),
        )
    }

    /// An exporter that sends through `reporter` (not closed by shutdown).
    pub fn with_reporter(reporter: ZipkinReporter) -> Self {
        ZipkinSpanExporter {
            reporter,
            owned: false,
            service_name: UNKNOWN_SERVICE.to_owned(),
            on_error: None,
            shutdown: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Sets the failed-export callback.
    pub fn on_error(mut self, handler: ExportErrorHandler) -> Self {
        self.on_error = Some(handler);
        self
    }

    /// The reporter used to send spans.
    pub fn reporter(&self) -> &ZipkinReporter {
        &self.reporter
    }

    /// The `localEndpoint.serviceName` in use (from the resource; `unknown_service` before
    /// the provider sets it).
    pub fn service_name(&self) -> &str {
        &self.service_name
    }

    /// Whether the exporter was shut down.
    pub fn is_shutdown(&self) -> bool {
        self.shutdown.load(Ordering::SeqCst)
    }
}

impl SpanExporter for ZipkinSpanExporter {
    fn export(
        &self,
        batch: Vec<SpanData>,
    ) -> impl std::future::Future<Output = OTelSdkResult> + Send {
        let spans: Vec<ZipkinSpan> = batch
            .iter()
            .map(|s| to_zipkin_span(s, &self.service_name))
            .collect();
        let reporter = self.reporter.clone();
        let on_error = self.on_error.clone();
        let shut = self.shutdown.load(Ordering::SeqCst);
        async move {
            if shut {
                return Err(OTelSdkError::AlreadyShutdown);
            }
            match reporter.report(&spans).await {
                Ok(()) => Ok(()),
                Err(err) => {
                    if let Some(handler) = on_error {
                        let _ = catch_unwind(AssertUnwindSafe(|| handler(&err)));
                    }
                    Err(OTelSdkError::InternalFailure(format!(
                        "zipkin export failed: {err}"
                    )))
                }
            }
        }
    }

    fn shutdown_with_timeout(&mut self, _timeout: Duration) -> OTelSdkResult {
        if !self.shutdown.swap(true, Ordering::SeqCst) {
            self.reporter.close_now();
        }
        Ok(())
    }

    fn set_resource(&mut self, resource: &Resource) {
        if let Some(name) = resource.get(&Key::from_static_str("service.name")) {
            let name = name.as_str();
            if !name.is_empty() {
                self.service_name = name.into_owned();
            }
        }
    }
}
