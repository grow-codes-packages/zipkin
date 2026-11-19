// Service code (Zipkin): the Zipkin v2 JSON span model, ingest normalization and the conversion
// of Zipkin spans into the SPEC §5 trace model.
//! Zipkin v2 span model ([`ZipkinSpan`], [`ZipkinEndpoint`], [`ZipkinAnnotation`]), the ingest
//! normalization applied by [`ZipkinReporter`](crate::ZipkinReporter) and the conversion into the
//! normalized [`Trace`] model of the tracing category.

use std::collections::BTreeMap;

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

use crate::core::{Error, Result};
use crate::tracing::{
    normalize_trace_id, DependencyLink, Span, SpanEvent, SpanKind, SpanStatus, StatusCode, Trace,
    TraceSummary,
};

/// Zipkin span kind; an absent kind means internal (local) work.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ZipkinKind {
    /// Client side of a request.
    Client,
    /// Server side of a request.
    Server,
    /// Message producer.
    Producer,
    /// Message consumer.
    Consumer,
}

impl ZipkinKind {
    /// Wire value: `CLIENT`, `SERVER`, `PRODUCER`, `CONSUMER`.
    pub fn as_str(self) -> &'static str {
        match self {
            ZipkinKind::Client => "CLIENT",
            ZipkinKind::Server => "SERVER",
            ZipkinKind::Producer => "PRODUCER",
            ZipkinKind::Consumer => "CONSUMER",
        }
    }

    /// Parses a kind in any letter case; `None` for anything else.
    pub fn parse(s: &str) -> Option<ZipkinKind> {
        match s.trim().to_ascii_uppercase().as_str() {
            "CLIENT" => Some(ZipkinKind::Client),
            "SERVER" => Some(ZipkinKind::Server),
            "PRODUCER" => Some(ZipkinKind::Producer),
            "CONSUMER" => Some(ZipkinKind::Consumer),
            _ => None,
        }
    }

    /// The SPEC §5 kind.
    pub fn span_kind(self) -> SpanKind {
        match self {
            ZipkinKind::Client => SpanKind::Client,
            ZipkinKind::Server => SpanKind::Server,
            ZipkinKind::Producer => SpanKind::Producer,
            ZipkinKind::Consumer => SpanKind::Consumer,
        }
    }
}

impl<'de> Deserialize<'de> for ZipkinKind {
    fn deserialize<D: Deserializer<'de>>(d: D) -> std::result::Result<Self, D::Error> {
        let s = String::deserialize(d)?;
        ZipkinKind::parse(&s)
            .ok_or_else(|| serde::de::Error::custom(format!("unknown span kind {s:?}")))
    }
}

/// Network endpoint of a span (`localEndpoint` / `remoteEndpoint`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ZipkinEndpoint {
    /// Service name (lower-cased by the server on ingest).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub service_name: Option<String>,
    /// IPv4 address.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ipv4: Option<String>,
    /// IPv6 address.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ipv6: Option<String>,
    /// Port.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
}

impl ZipkinEndpoint {
    /// An endpoint with only a service name.
    pub fn service(name: impl Into<String>) -> Self {
        ZipkinEndpoint {
            service_name: Some(name.into()),
            ..Default::default()
        }
    }

    fn normalized(&self) -> Option<ZipkinEndpoint> {
        let keep = |v: &Option<String>| v.clone().filter(|s| !s.is_empty());
        let out = ZipkinEndpoint {
            service_name: keep(&self.service_name),
            ipv4: keep(&self.ipv4),
            ipv6: keep(&self.ipv6),
            port: self.port.filter(|p| *p != 0),
        };
        (out != ZipkinEndpoint::default()).then_some(out)
    }
}

/// A timestamped event on a span.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ZipkinAnnotation {
    /// Epoch microseconds.
    pub timestamp: u64,
    /// Event text.
    pub value: String,
}

/// A Zipkin v2 span exactly as the server stores and returns it (every tag value is a string).
///
/// Build one with [`ZipkinSpan::new`] and the `with_*` methods; ids may be upper-case, they are
/// lower-cased by [`normalize_zipkin_span`] before sending.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ZipkinSpan {
    /// 16 or 32 lower-case hex characters.
    pub trace_id: String,
    /// 16 lower-case hex characters; absent on a root span.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    /// 16 lower-case hex characters.
    pub id: String,
    /// Kind; `None` means internal.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<ZipkinKind>,
    /// Name (lower-cased by the server on ingest).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Start, epoch microseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<u64>,
    /// Duration in microseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration: Option<u64>,
    /// Local endpoint (the reporting service).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_endpoint: Option<ZipkinEndpoint>,
    /// Remote endpoint (the peer).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remote_endpoint: Option<ZipkinEndpoint>,
    /// Annotations (events).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub annotations: Vec<ZipkinAnnotation>,
    /// Tags (string values).
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub tags: BTreeMap<String, String>,
    /// Force-store this span (B3 debug).
    #[serde(default, skip_serializing_if = "is_false")]
    pub debug: bool,
    /// A server span that shares its id with the client span.
    #[serde(default, skip_serializing_if = "is_false")]
    pub shared: bool,
}

fn is_false(b: &bool) -> bool {
    !*b
}

impl ZipkinSpan {
    /// A span with ids only.
    pub fn new(trace_id: impl Into<String>, id: impl Into<String>) -> Self {
        ZipkinSpan {
            trace_id: trace_id.into(),
            id: id.into(),
            ..Default::default()
        }
    }
    /// Sets the parent span id.
    pub fn with_parent_id(mut self, parent_id: impl Into<String>) -> Self {
        self.parent_id = Some(parent_id.into());
        self
    }
    /// Sets the kind.
    pub fn with_kind(mut self, kind: ZipkinKind) -> Self {
        self.kind = Some(kind);
        self
    }
    /// Sets the name.
    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }
    /// Sets start (epoch µs) and duration (µs).
    pub fn with_timing(mut self, timestamp_micros: u64, duration_micros: u64) -> Self {
        self.timestamp = Some(timestamp_micros);
        self.duration = Some(duration_micros);
        self
    }
    /// Sets `localEndpoint.serviceName`.
    pub fn with_local_service(mut self, service: impl Into<String>) -> Self {
        self.local_endpoint
            .get_or_insert_with(ZipkinEndpoint::default)
            .service_name = Some(service.into());
        self
    }
    /// Sets the local endpoint.
    pub fn with_local_endpoint(mut self, endpoint: ZipkinEndpoint) -> Self {
        self.local_endpoint = Some(endpoint);
        self
    }
    /// Sets the remote endpoint.
    pub fn with_remote_endpoint(mut self, endpoint: ZipkinEndpoint) -> Self {
        self.remote_endpoint = Some(endpoint);
        self
    }
    /// Adds an annotation.
    pub fn with_annotation(mut self, timestamp_micros: u64, value: impl Into<String>) -> Self {
        self.annotations.push(ZipkinAnnotation {
            timestamp: timestamp_micros,
            value: value.into(),
        });
        self
    }
    /// Adds a tag; numbers and booleans become strings (`7`, `1.5`, `true`), other JSON values
    /// their JSON text (see [`tag_value`]).
    pub fn with_tag(mut self, key: impl Into<String>, value: impl Into<Value>) -> Self {
        self.tags.insert(key.into(), tag_value(&value.into()));
        self
    }
    /// Sets the debug flag.
    pub fn with_debug(mut self, debug: bool) -> Self {
        self.debug = debug;
        self
    }
    /// Sets the shared flag.
    pub fn with_shared(mut self, shared: bool) -> Self {
        self.shared = shared;
        self
    }
}

/// A dependency edge with Zipkin's `errorCount` (0 when the server omits it).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ZipkinDependencyLink {
    /// Calling service.
    pub parent: String,
    /// Called service.
    pub child: String,
    /// Number of calls.
    #[serde(default)]
    pub call_count: u64,
    /// Number of failed calls.
    #[serde(default)]
    pub error_count: u64,
}

impl From<ZipkinDependencyLink> for DependencyLink {
    fn from(l: ZipkinDependencyLink) -> Self {
        DependencyLink {
            parent: l.parent,
            child: l.child,
            call_count: l.call_count,
        }
    }
}

/// Converts a tag value to Zipkin's string form: strings as-is, numbers and booleans as text
/// (`7`, `1.5`, `true`), anything else as JSON (`null` → `"null"`).
pub fn tag_value(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        other => other.to_string(),
    }
}

fn is_hex_id(s: &str, lens: &[usize]) -> bool {
    lens.contains(&s.len())
        && s.bytes().all(|b| b.is_ascii_hexdigit())
        && !s.bytes().all(|b| b == b'0')
}

/// Lower-cases and validates a Zipkin trace id (16 or 32 hex characters, not all zero).
/// `VALIDATION` without a request otherwise.
pub fn zipkin_trace_id(id: &str, operation: &str) -> Result<String> {
    let lower = id.trim().to_ascii_lowercase();
    if !is_hex_id(&lower, &[16, 32]) {
        return Err(Error::validation(format!(
            "{operation}: trace id must be 16 or 32 hex characters"
        )));
    }
    Ok(lower)
}

fn span_id(id: &str, what: &str, operation: &str) -> Result<String> {
    let lower = id.trim().to_ascii_lowercase();
    if !is_hex_id(&lower, &[16]) {
        return Err(Error::validation(format!(
            "{operation}: {what} must be 16 hex characters"
        )));
    }
    Ok(lower)
}

/// Normalizes one span for ingest (the rules Zipkin enforces, applied locally so one bad span
/// never makes the server reject the whole batch): ids lower-cased and validated, zero
/// timestamps/durations and empty fields dropped, annotations need a value and a timestamp.
/// `VALIDATION` naming the problem otherwise.
pub fn normalize_zipkin_span(span: &ZipkinSpan, operation: &str) -> Result<ZipkinSpan> {
    let mut annotations = Vec::with_capacity(span.annotations.len());
    for a in &span.annotations {
        if a.value.is_empty() {
            return Err(Error::validation(format!(
                "{operation}: annotation value must be a non-empty string"
            )));
        }
        if a.timestamp == 0 {
            return Err(Error::validation(format!(
                "{operation}: annotation timestamp is required"
            )));
        }
        annotations.push(a.clone());
    }
    Ok(ZipkinSpan {
        trace_id: zipkin_trace_id(&span.trace_id, operation)?,
        parent_id: match span.parent_id.as_deref() {
            Some(p) if !p.is_empty() => Some(span_id(p, "parentId", operation)?),
            _ => None,
        },
        id: span_id(&span.id, "span id", operation)?,
        kind: span.kind,
        name: span.name.clone().filter(|n| !n.is_empty()),
        timestamp: span.timestamp.filter(|t| *t > 0),
        duration: span.duration.filter(|d| *d > 0),
        local_endpoint: span.local_endpoint.as_ref().and_then(|e| e.normalized()),
        remote_endpoint: span.remote_endpoint.as_ref().and_then(|e| e.normalized()),
        annotations,
        tags: span.tags.clone(),
        debug: span.debug,
        shared: span.shared,
    })
}

// ---------------------------------------------------------------------------------------------
// Decoding responses

/// Keeps the well-formed spans of a response body (`ZipkinSpan[]`).
pub fn zipkin_spans_from_value(body: &Value) -> Vec<ZipkinSpan> {
    match body {
        Value::Array(items) => items
            .iter()
            .filter(|v| v.is_object())
            .filter_map(|v| ZipkinSpan::deserialize(v).ok())
            .collect(),
        _ => Vec::new(),
    }
}

/// Keeps the well-formed traces of a response body (`ZipkinSpan[][]`), dropping empty ones.
pub fn zipkin_traces_from_value(body: &Value) -> Vec<Vec<ZipkinSpan>> {
    match body {
        Value::Array(items) => items
            .iter()
            .map(zipkin_spans_from_value)
            .filter(|t| !t.is_empty())
            .collect(),
        _ => Vec::new(),
    }
}

/// Decodes a string list response (`string[]`), skipping non-strings.
pub fn strings_from_value(body: &Value) -> Vec<String> {
    match body {
        Value::Array(items) => items
            .iter()
            .filter_map(|v| v.as_str().map(str::to_owned))
            .collect(),
        _ => Vec::new(),
    }
}

/// Decodes `/api/v2/dependencies`, defaulting `errorCount` to 0, sorted by parent then child.
pub fn dependencies_from_value(body: &Value) -> Vec<ZipkinDependencyLink> {
    let mut out: Vec<ZipkinDependencyLink> = match body {
        Value::Array(items) => items
            .iter()
            .filter_map(|v| {
                Some(ZipkinDependencyLink {
                    parent: v.get("parent")?.as_str()?.to_owned(),
                    child: v.get("child")?.as_str()?.to_owned(),
                    call_count: v.get("callCount").and_then(Value::as_u64).unwrap_or(0),
                    error_count: v.get("errorCount").and_then(Value::as_u64).unwrap_or(0),
                })
            })
            .collect(),
        _ => Vec::new(),
    };
    out.sort_by(|a, b| a.parent.cmp(&b.parent).then_with(|| a.child.cmp(&b.child)));
    out
}

// ---------------------------------------------------------------------------------------------
// Zipkin → SPEC §5 model

/// Decodes an annotation into an event: a value of the form `{"<name>": {…attributes}}` (how
/// the [`ZipkinSpanExporter`](crate::ZipkinSpanExporter) encodes an event with attributes)
/// becomes that name and attributes; any other value is the event name with no attributes.
pub fn annotation_to_event(annotation: &ZipkinAnnotation) -> SpanEvent {
    let value = &annotation.value;
    if value.starts_with('{') && value.ends_with('}') {
        if let Ok(Value::Object(map)) = serde_json::from_str::<Value>(value) {
            if map.len() == 1 {
                let (name, attrs) = map.into_iter().next().expect("one entry");
                if let Value::Object(attrs) = attrs {
                    return SpanEvent {
                        name,
                        time_micros: annotation.timestamp,
                        attributes: attrs.into_iter().collect(),
                    };
                }
            }
        }
    }
    SpanEvent {
        name: value.clone(),
        time_micros: annotation.timestamp,
        attributes: Default::default(),
    }
}

/// Converts a Zipkin span into the SPEC §5 model: trace id padded to 32 characters, kind
/// lower-cased (absent → `internal`), `service_name` = `localEndpoint.serviceName` (or `""`),
/// attributes = tags, annotations → events, status from the `error` tag (error, message = its
/// value) or `otel.status_code` (`OK`/`ERROR`), no links. Endpoints, `shared` and `debug` stay
/// on the raw [`ZipkinSpan`].
pub fn zipkin_span_to_span(span: &ZipkinSpan) -> Span {
    let start = span.timestamp.unwrap_or(0);
    let duration = span.duration.unwrap_or(0);
    let otel_status = span
        .tags
        .get("otel.status_code")
        .map(|s| s.to_ascii_uppercase());
    let status = match (span.tags.get("error"), otel_status.as_deref()) {
        (Some(e), _) => SpanStatus {
            code: StatusCode::Error,
            message: Some(e.clone()).filter(|m| !m.is_empty()),
        },
        (None, Some("ERROR")) => SpanStatus {
            code: StatusCode::Error,
            message: None,
        },
        (None, Some("OK")) => SpanStatus {
            code: StatusCode::Ok,
            message: None,
        },
        _ => SpanStatus::default(),
    };
    Span {
        trace_id: normalize_trace_id(&span.trace_id)
            .unwrap_or_else(|_| span.trace_id.to_ascii_lowercase()),
        span_id: span.id.to_ascii_lowercase(),
        parent_span_id: span
            .parent_id
            .as_deref()
            .filter(|p| !p.is_empty())
            .map(str::to_ascii_lowercase),
        name: span.name.clone().unwrap_or_default(),
        kind: span.kind.map_or(SpanKind::Internal, ZipkinKind::span_kind),
        service_name: span
            .local_endpoint
            .as_ref()
            .and_then(|e| e.service_name.clone())
            .unwrap_or_default(),
        start_micros: start,
        end_micros: start + duration,
        duration_micros: duration,
        status,
        attributes: span
            .tags
            .iter()
            .map(|(k, v)| (k.clone(), Value::String(v.clone())))
            .collect(),
        events: span.annotations.iter().map(annotation_to_event).collect(),
        links: Vec::new(),
    }
}

/// Converts the spans of one Zipkin trace into a [`Trace`] (spans sorted); `None` when empty.
pub fn zipkin_trace_to_trace(spans: &[ZipkinSpan]) -> Option<Trace> {
    let first = spans.first()?;
    let id =
        normalize_trace_id(&first.trace_id).unwrap_or_else(|_| first.trace_id.to_ascii_lowercase());
    Some(Trace::new(
        id,
        spans.iter().map(zipkin_span_to_span).collect(),
    ))
}

/// Summarises one Zipkin trace (root = the span without a parent in the trace, else the
/// earliest); `None` when empty.
pub fn summarize_zipkin_trace(spans: &[ZipkinSpan]) -> Option<TraceSummary> {
    // The tree's first root is the earliest span whose parent is not in the trace.
    zipkin_trace_to_trace(spans).map(|t| t.summary())
}
