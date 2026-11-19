// Service code (Zipkin): ZipkinQueryClient — the Zipkin v2 query API and the SPEC §5
// TraceQueryClient.
//! [`ZipkinQueryClient`]: the native Zipkin v2 query API plus the category
//! [`TraceQueryClient`].

use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use serde_json::Value;

use crate::client::ZipkinClient;
use crate::config::ZipkinConfig;
use crate::core::{Error, Result};
use crate::model::{
    dependencies_from_value, strings_from_value, summarize_zipkin_trace, zipkin_spans_from_value,
    zipkin_trace_id, zipkin_trace_to_trace, zipkin_traces_from_value, ZipkinDependencyLink,
    ZipkinSpan,
};
use crate::tracing::{
    wait_for_trace, DependencyLink, DependencyOptions, Trace, TraceQueryClient, TraceSearchQuery,
    TraceSummary, WaitForTraceOptions,
};

/// Default `limit` of [`ZipkinQueryClient::search_traces`] (SPEC §5).
pub const DEFAULT_SEARCH_LIMIT: usize = 20;
/// Window of [`ZipkinQueryClient::search_traces`] when the query has no `start`.
pub const DEFAULT_SEARCH_WINDOW: Duration = Duration::from_secs(3600);
/// Most trace ids sent in one `/api/v2/traceMany` request: 100 × 33 characters keeps the
/// request line under Armeria's default HTTP/1 limit of 4096 bytes.
pub const TRACE_MANY_CHUNK: usize = 100;

const AND: &str = " and ";

/// Filter of [`ZipkinQueryClient::get_traces`] (`GET /api/v2/traces`). Every field is optional;
/// unset fields use the server defaults (`endTs` = now, `lookback` = 24 h, `limit` = 10).
/// `annotation_query`, `tags` and `annotations` are combined with ` and ` (Zipkin supports AND
/// only).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ZipkinTraceQuery {
    /// Local service name (matched case-insensitively).
    pub service_name: Option<String>,
    /// Remote service name (`remoteEndpoint.serviceName`) seen on `service_name`'s spans.
    pub remote_service_name: Option<String>,
    /// Span name.
    pub span_name: Option<String>,
    /// Raw Zipkin annotation query, e.g. `http.method=GET and error`.
    pub annotation_query: Option<String>,
    /// Exact tag matches (`key=value`). A key or value that contains ` and ` (or a key that
    /// contains `=`) cannot be expressed in Zipkin's syntax: that term is matched client-side
    /// after the query (so fewer than `limit` traces may come back).
    pub tags: BTreeMap<String, String>,
    /// Bare terms: a tag key or an annotation value (`error`, `cache.miss`).
    pub annotations: Vec<String>,
    /// Minimum span duration (microsecond precision).
    pub min_duration: Option<Duration>,
    /// Maximum span duration (needs `min_duration`).
    pub max_duration: Option<Duration>,
    /// End of the window. Default: server now.
    pub end_ts: Option<SystemTime>,
    /// Window length before `end_ts` (millisecond precision). Default: server
    /// `QUERY_LOOKBACK` (24 h).
    pub lookback: Option<Duration>,
    /// Maximum number of traces. Default: server default (10).
    pub limit: Option<usize>,
}

impl ZipkinTraceQuery {
    /// A query for one service.
    pub fn service(service_name: impl Into<String>) -> Self {
        ZipkinTraceQuery {
            service_name: Some(service_name.into()),
            ..Default::default()
        }
    }
    /// Sets the remote service name.
    pub fn remote_service_name(mut self, name: impl Into<String>) -> Self {
        self.remote_service_name = Some(name.into());
        self
    }
    /// Sets the span name.
    pub fn span_name(mut self, name: impl Into<String>) -> Self {
        self.span_name = Some(name.into());
        self
    }
    /// Sets the raw annotation query.
    pub fn annotation_query(mut self, query: impl Into<String>) -> Self {
        self.annotation_query = Some(query.into());
        self
    }
    /// Adds an exact tag match.
    pub fn tag(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.tags.insert(key.into(), value.into());
        self
    }
    /// Adds a bare term (tag key or annotation value).
    pub fn annotation(mut self, term: impl Into<String>) -> Self {
        self.annotations.push(term.into());
        self
    }
    /// Sets the duration range (`max` needs `min`).
    pub fn duration(mut self, min: Option<Duration>, max: Option<Duration>) -> Self {
        self.min_duration = min;
        self.max_duration = max;
        self
    }
    /// Sets the window: `end_ts` and `lookback`.
    pub fn window(mut self, end_ts: SystemTime, lookback: Duration) -> Self {
        self.end_ts = Some(end_ts);
        self.lookback = Some(lookback);
        self
    }
    /// Sets the limit.
    pub fn limit(mut self, limit: usize) -> Self {
        self.limit = Some(limit);
        self
    }
}

/// Window of [`ZipkinQueryClient::get_dependencies`]: either the category window
/// (`start`/`end`) or Zipkin's native `end_ts` + `lookback` (these win when given).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ZipkinDependencyOptions {
    /// Range start (gives `lookback = end - start`).
    pub start: Option<SystemTime>,
    /// Range end (default now).
    pub end: Option<SystemTime>,
    /// End of the window; wins over `end`.
    pub end_ts: Option<SystemTime>,
    /// Window length; wins over `start`. Default: `end - start`, else the server default (24 h).
    pub lookback: Option<Duration>,
}

impl ZipkinDependencyOptions {
    /// Native window: `end_ts` and `lookback`.
    pub fn window(end_ts: SystemTime, lookback: Duration) -> Self {
        ZipkinDependencyOptions {
            end_ts: Some(end_ts),
            lookback: Some(lookback),
            ..Default::default()
        }
    }
}

impl From<DependencyOptions> for ZipkinDependencyOptions {
    fn from(o: DependencyOptions) -> Self {
        ZipkinDependencyOptions {
            start: o.start,
            end: o.end,
            ..Default::default()
        }
    }
}

#[derive(Debug, Clone)]
struct Term {
    key: String,
    value: Option<String>,
}

impl Term {
    fn server_side(&self) -> bool {
        !self.key.contains(AND)
            && !self.key.contains('=')
            && !self.value.as_deref().unwrap_or("").contains(AND)
    }
    fn text(&self) -> String {
        match &self.value {
            Some(v) => format!("{}={v}", self.key),
            None => self.key.clone(),
        }
    }
    fn matches(&self, spans: &[ZipkinSpan]) -> bool {
        spans.iter().any(|s| match &self.value {
            Some(v) => s.tags.get(&self.key) == Some(v),
            None => {
                s.tags.contains_key(&self.key) || s.annotations.iter().any(|a| a.value == self.key)
            }
        })
    }
}

fn terms(tags: &BTreeMap<String, String>, annotations: &[String]) -> Vec<Term> {
    tags.iter()
        .map(|(k, v)| Term {
            key: k.clone(),
            value: Some(v.clone()),
        })
        .chain(annotations.iter().map(|k| Term {
            key: k.clone(),
            value: None,
        }))
        .collect()
}

/// Builds a Zipkin `annotationQuery` from exact tag matches and bare terms (joined with
/// ` and `); `None` when both are empty. `VALIDATION` for a term Zipkin cannot express (empty,
/// contains ` and `, or a key with `=`).
pub fn build_annotation_query(
    tags: &BTreeMap<String, String>,
    annotations: &[String],
) -> Result<Option<String>> {
    let terms = terms(tags, annotations);
    for term in &terms {
        if term.key.is_empty() {
            return Err(Error::validation("zipkin.annotationQuery: empty term"));
        }
        if !term.server_side() {
            return Err(Error::validation(
                "zipkin.annotationQuery: a term must not contain \" and \" (or \"=\" in a key)",
            ));
        }
    }
    Ok((!terms.is_empty()).then(|| terms.iter().map(Term::text).collect::<Vec<_>>().join(AND)))
}

fn epoch_ms(t: SystemTime, what: &str, operation: &str) -> Result<u64> {
    let ms = t
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    if ms == 0 {
        return Err(Error::validation(format!(
            "{operation}: {what} must be a positive time"
        )));
    }
    Ok(ms)
}

fn lookback_ms(d: Duration) -> u64 {
    (d.as_millis() as u64).max(1)
}

fn non_empty<'a>(value: &'a str, what: &str, operation: &str) -> Result<&'a str> {
    if value.trim().is_empty() {
        return Err(Error::validation(format!(
            "{operation}: {what} is required"
        )));
    }
    Ok(value)
}

fn blank_to_none(value: &Option<String>) -> Option<&str> {
    value.as_deref().filter(|v| !v.trim().is_empty())
}

/// Reads traces back from Zipkin (port 9411): the native v2 query API
/// ([`get_trace_spans`](Self::get_trace_spans), [`get_traces`](Self::get_traces),
/// [`get_trace_many`](Self::get_trace_many), [`list_services`](Self::list_services),
/// [`list_spans`](Self::list_spans), [`list_remote_services`](Self::list_remote_services),
/// [`get_dependencies`](Self::get_dependencies), [`autocomplete_keys`](Self::autocomplete_keys),
/// [`autocomplete_values`](Self::autocomplete_values)) and the SPEC §5 [`TraceQueryClient`]
/// ([`get_trace`](Self::get_trace), [`search_traces`](Self::search_traces),
/// [`list_operations`](Self::list_operations), [`wait_for_trace`](Self::wait_for_trace)) on top
/// of it. Every call is a GET (retried on 5xx, 429 and network errors).
///
/// Get one from [`ZipkinClient::query`] (shares the client's connections) or create it from a
/// config with [`ZipkinQueryClient::new`] (owns its connections; call [`close`](Self::close)).
/// Cheap to clone.
#[derive(Debug, Clone)]
pub struct ZipkinQueryClient {
    client: ZipkinClient,
    owned: bool,
}

impl ZipkinQueryClient {
    /// A query client with its own connections.
    pub fn new(config: ZipkinConfig) -> Result<Self> {
        Ok(ZipkinQueryClient {
            client: ZipkinClient::new(config)?,
            owned: true,
        })
    }

    /// A standalone query client from `GROW_ZIPKIN_*` / `ZIPKIN_URL`.
    pub fn from_env() -> Result<Self> {
        Self::new(ZipkinConfig::from_env()?)
    }

    pub(crate) fn shared(client: ZipkinClient) -> Self {
        ZipkinQueryClient {
            client,
            owned: false,
        }
    }

    async fn get(
        &self,
        path: &str,
        operation: &str,
        query: &[(&str, Option<String>)],
        allow_404: bool,
    ) -> Result<Option<Value>> {
        let mut req = self.client.transport().get(path).operation(operation);
        for (name, value) in query {
            if let Some(v) = value {
                req = req.query(*name, v.clone());
            }
        }
        if allow_404 {
            req = req.ok_status(404);
        }
        let resp = req.send().await?;
        if resp.status() == 404 {
            return Ok(None);
        }
        if resp.bytes().is_empty() {
            return Ok(Some(Value::Null));
        }
        Ok(Some(resp.json::<Value>()?))
    }

    async fn get_value(
        &self,
        path: &str,
        operation: &str,
        query: &[(&str, Option<String>)],
    ) -> Result<Value> {
        Ok(self
            .get(path, operation, query, false)
            .await?
            .unwrap_or(Value::Null))
    }

    /// One trace in the native model: `GET /api/v2/trace/{traceId}`. `None` when the trace does
    /// not exist (404). The id is lower-cased; anything but 16 or 32 hex characters fails with
    /// `VALIDATION` without a request. Spans come back in storage order.
    pub async fn get_trace_spans(&self, trace_id: &str) -> Result<Option<Vec<ZipkinSpan>>> {
        let id = zipkin_trace_id(trace_id, "zipkin.getTrace")?;
        let body = self
            .get(
                &format!("/api/v2/trace/{id}"),
                &format!("zipkin.getTrace {id}"),
                &[],
                true,
            )
            .await?;
        Ok(body
            .map(|b| zipkin_spans_from_value(&b))
            .filter(|s| !s.is_empty()))
    }

    /// One trace in the SPEC §5 model (see [`zipkin_span_to_span`](crate::zipkin_span_to_span));
    /// `None` when it does not exist.
    pub async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        Ok(self
            .get_trace_spans(trace_id)
            .await?
            .and_then(|s| zipkin_trace_to_trace(&s)))
    }

    /// Searches traces: `GET /api/v2/traces`, newest first, one `Vec<ZipkinSpan>` per trace. No
    /// match returns `[]`. Invalid filters (`limit` 0, `max_duration` without or below
    /// `min_duration`, a zero `lookback`, `end_ts` at or before the epoch) fail with
    /// `VALIDATION` without a request.
    pub async fn get_traces(&self, query: &ZipkinTraceQuery) -> Result<Vec<Vec<ZipkinSpan>>> {
        let operation = "zipkin.getTraces";
        let micros = |d: Duration| d.as_micros() as u64;
        let min = query.min_duration.map(micros);
        let max = query.max_duration.map(micros);
        match (min, max) {
            (None, Some(_)) => {
                return Err(Error::validation(format!(
                    "{operation}: maxDuration needs minDuration"
                )))
            }
            (Some(lo), Some(hi)) if hi < lo => {
                return Err(Error::validation(format!(
                    "{operation}: maxDuration must not be below minDuration"
                )))
            }
            _ => {}
        }
        if query.limit == Some(0) {
            return Err(Error::validation(format!(
                "{operation}: limit must be a positive integer"
            )));
        }
        if query.lookback.is_some_and(|d| d.is_zero()) {
            return Err(Error::validation(format!(
                "{operation}: lookback must be positive"
            )));
        }
        let end_ts = query
            .end_ts
            .map(|t| epoch_ms(t, "endTs", operation))
            .transpose()?;
        let terms = terms(&query.tags, &query.annotations);
        if terms.iter().any(|t| t.key.is_empty()) {
            return Err(Error::validation(format!(
                "{operation}: tag keys and annotations must not be empty"
            )));
        }
        let (server, local): (Vec<Term>, Vec<Term>) =
            terms.into_iter().partition(Term::server_side);
        let mut parts: Vec<String> = Vec::new();
        if let Some(raw) = blank_to_none(&query.annotation_query) {
            parts.push(raw.trim().to_owned());
        }
        parts.extend(server.iter().map(Term::text));
        let body = self
            .get_value(
                "/api/v2/traces",
                operation,
                &[
                    (
                        "serviceName",
                        blank_to_none(&query.service_name).map(str::to_owned),
                    ),
                    (
                        "remoteServiceName",
                        blank_to_none(&query.remote_service_name).map(str::to_owned),
                    ),
                    (
                        "spanName",
                        blank_to_none(&query.span_name).map(str::to_owned),
                    ),
                    (
                        "annotationQuery",
                        (!parts.is_empty()).then(|| parts.join(AND)),
                    ),
                    ("minDuration", min.map(|v| v.to_string())),
                    ("maxDuration", max.map(|v| v.to_string())),
                    ("endTs", end_ts.map(|v| v.to_string())),
                    (
                        "lookback",
                        query.lookback.map(|d| lookback_ms(d).to_string()),
                    ),
                    ("limit", query.limit.map(|v| v.to_string())),
                ],
            )
            .await?;
        let mut traces = zipkin_traces_from_value(&body);
        if !local.is_empty() {
            traces.retain(|t| local.iter().all(|term| term.matches(t)));
        }
        Ok(traces)
    }

    /// Several traces by id: `GET /api/v2/traceMany`. Ids are lower-cased and de-duplicated (a
    /// 16-character id equals its zero-padded 32-character form); an empty list returns `[]`
    /// without a request, a single id uses `GET /api/v2/trace/{id}`, more are fetched in chunks
    /// of [`TRACE_MANY_CHUNK`]. Missing traces are omitted; the result follows the request
    /// order.
    pub async fn get_trace_many<S: AsRef<str>>(
        &self,
        trace_ids: &[S],
    ) -> Result<Vec<Vec<ZipkinSpan>>> {
        let operation = "zipkin.getTraceMany";
        let mut ids: Vec<String> = Vec::new();
        let mut seen = HashSet::new();
        for raw in trace_ids {
            let id = zipkin_trace_id(raw.as_ref(), operation)?;
            if seen.insert(format!("{id:0>32}")) {
                ids.push(id);
            }
        }
        let mut by_id: HashMap<String, Vec<ZipkinSpan>> = HashMap::new();
        for chunk in ids.chunks(TRACE_MANY_CHUNK) {
            let traces = if chunk.len() == 1 {
                self.get_trace_spans(&chunk[0])
                    .await?
                    .into_iter()
                    .collect::<Vec<_>>()
            } else {
                let body = self
                    .get_value(
                        "/api/v2/traceMany",
                        operation,
                        &[("traceIds", Some(chunk.join(",")))],
                    )
                    .await?;
                zipkin_traces_from_value(&body)
            };
            for t in traces {
                let key = format!("{:0>32}", t[0].trace_id.to_ascii_lowercase());
                by_id.insert(key, t);
            }
        }
        Ok(ids
            .iter()
            .filter_map(|id| by_id.remove(&format!("{id:0>32}")))
            .collect())
    }

    /// SPEC §5 search: `service` → `serviceName`, `operation` → `spanName`, `tags` → exact tag
    /// terms (see [`ZipkinTraceQuery::tags`]), durations, `endTs` = `end` (default now),
    /// `lookback` = `end - start` (default [`DEFAULT_SEARCH_WINDOW`], 1 h, without `start`),
    /// `limit` (default 20). Returns one summary per trace (root = the span without a parent,
    /// else the earliest).
    pub async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let operation = "zipkin.searchTraces";
        let end = query.end.unwrap_or_else(SystemTime::now);
        let end_ms = epoch_ms(end, "end", operation)?;
        let lookback = match query.start {
            Some(start) => {
                let start_ms = epoch_ms(start, "start", operation)?;
                if start_ms > end_ms {
                    return Err(Error::validation(format!(
                        "{operation}: start must not be after end"
                    )));
                }
                Duration::from_millis((end_ms - start_ms).max(1))
            }
            None => DEFAULT_SEARCH_WINDOW,
        };
        let native = ZipkinTraceQuery {
            service_name: query.service.clone(),
            span_name: query.operation.clone(),
            tags: query.tags.clone(),
            min_duration: query.min_duration,
            max_duration: query.max_duration,
            end_ts: Some(UNIX_EPOCH + Duration::from_millis(end_ms)),
            lookback: Some(lookback),
            limit: Some(query.limit),
            ..Default::default()
        };
        Ok(self
            .get_traces(&native)
            .await?
            .iter()
            .filter_map(|t| summarize_zipkin_trace(t))
            .collect())
    }

    /// Service names that reported spans: `GET /api/v2/services` (sorted, lower-case).
    pub async fn list_services(&self) -> Result<Vec<String>> {
        let body = self
            .get_value("/api/v2/services", "zipkin.listServices", &[])
            .await?;
        let mut out = strings_from_value(&body);
        out.sort();
        Ok(out)
    }

    /// Span names of a service: `GET /api/v2/spans?serviceName=` (sorted; unknown service →
    /// `[]`).
    pub async fn list_spans(&self, service_name: &str) -> Result<Vec<String>> {
        let operation = "zipkin.listSpans";
        let name = non_empty(service_name, "serviceName", operation)?;
        let body = self
            .get_value(
                "/api/v2/spans",
                operation,
                &[("serviceName", Some(name.to_owned()))],
            )
            .await?;
        let mut out = strings_from_value(&body);
        out.sort();
        Ok(out)
    }

    /// SPEC §5 alias of [`list_spans`](Self::list_spans).
    pub async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.list_spans(service).await
    }

    /// Remote services called by a service (`remoteEndpoint.serviceName` on its spans):
    /// `GET /api/v2/remoteServices?serviceName=` (sorted).
    pub async fn list_remote_services(&self, service_name: &str) -> Result<Vec<String>> {
        let operation = "zipkin.listRemoteServices";
        let name = non_empty(service_name, "serviceName", operation)?;
        let body = self
            .get_value(
                "/api/v2/remoteServices",
                operation,
                &[("serviceName", Some(name.to_owned()))],
            )
            .await?;
        let mut out = strings_from_value(&body);
        out.sort();
        Ok(out)
    }

    /// Service dependency edges: `GET /api/v2/dependencies?endTs=&lookback=`, sorted by parent
    /// and child, `error_count` defaulting to 0. Accepts [`ZipkinDependencyOptions`] or the
    /// category [`DependencyOptions`]. In-memory storage computes edges on read; persistent
    /// backends need the zipkin-dependencies job, so `[]` is normal there.
    pub async fn get_dependencies(
        &self,
        opts: impl Into<ZipkinDependencyOptions>,
    ) -> Result<Vec<ZipkinDependencyLink>> {
        let operation = "zipkin.getDependencies";
        let opts = opts.into();
        let end = opts.end_ts.or(opts.end).unwrap_or_else(SystemTime::now);
        let end_ms = epoch_ms(end, "endTs", operation)?;
        let lookback = match (opts.lookback, opts.start) {
            (Some(d), _) => Some(d.as_millis() as i128),
            (None, Some(start)) => {
                Some(end_ms as i128 - epoch_ms(start, "start", operation)? as i128)
            }
            (None, None) => None,
        };
        if lookback.is_some_and(|l| l <= 0) {
            return Err(Error::validation(format!(
                "{operation}: lookback must be a positive duration"
            )));
        }
        let body = self
            .get_value(
                "/api/v2/dependencies",
                operation,
                &[
                    ("endTs", Some(end_ms.to_string())),
                    ("lookback", lookback.map(|l| l.to_string())),
                ],
            )
            .await?;
        Ok(dependencies_from_value(&body))
    }

    /// Tag keys the server indexes for autocomplete: `GET /api/v2/autocompleteKeys` (the
    /// server's `AUTOCOMPLETE_KEYS`, `[]` when unset).
    pub async fn autocomplete_keys(&self) -> Result<Vec<String>> {
        let body = self
            .get_value("/api/v2/autocompleteKeys", "zipkin.autocompleteKeys", &[])
            .await?;
        Ok(strings_from_value(&body))
    }

    /// Distinct values seen for an autocomplete key: `GET /api/v2/autocompleteValues?key=`
    /// (sorted; `[]` for a key that is not in `AUTOCOMPLETE_KEYS`).
    pub async fn autocomplete_values(&self, key: &str) -> Result<Vec<String>> {
        let operation = "zipkin.autocompleteValues";
        let key = non_empty(key, "key", operation)?;
        let body = self
            .get_value(
                "/api/v2/autocompleteValues",
                operation,
                &[("key", Some(key.to_owned()))],
            )
            .await?;
        let mut out = strings_from_value(&body);
        out.sort();
        Ok(out)
    }

    /// Polls [`get_trace`](Self::get_trace) until the trace has `opts.min_spans` spans
    /// (`TIMEOUT` otherwise; a malformed id fails at once with `VALIDATION`).
    pub async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        wait_for_trace(self, trace_id, opts).await
    }

    /// Releases connections when this client owns them (no-op for [`ZipkinClient::query`]).
    /// Idempotent.
    pub async fn close(&self) {
        if self.owned {
            self.client.close().await;
        }
    }
}

#[async_trait]
impl TraceQueryClient for ZipkinQueryClient {
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        ZipkinQueryClient::get_trace(self, trace_id).await
    }
    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        ZipkinQueryClient::search_traces(self, query).await
    }
    async fn list_services(&self) -> Result<Vec<String>> {
        ZipkinQueryClient::list_services(self).await
    }
    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        ZipkinQueryClient::list_operations(self, service).await
    }
    async fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        Ok(ZipkinQueryClient::get_dependencies(self, opts)
            .await?
            .into_iter()
            .map(DependencyLink::from)
            .collect())
    }
}
