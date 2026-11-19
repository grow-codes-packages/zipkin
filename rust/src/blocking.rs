// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! Blocking wrapper (feature `blocking`). Each client owns a tokio runtime with one worker
//! thread, so background work (batched exports, retries' timers) keeps running between
//! blocking calls.
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.

use std::future::Future;
use std::time::Duration;

use crate::core::{Error, HealthStatus, Result};
use crate::tracing::{Trace, TraceSearchQuery, TraceSummary, WaitForTraceOptions};
use crate::{
    ZipkinClient as AsyncClient, ZipkinConfig, ZipkinDependencyLink, ZipkinDependencyOptions,
    ZipkinQueryClient, ZipkinServerInfo, ZipkinSpan, ZipkinTraceQuery,
};

/// Blocking client for Zipkin with the same methods as the async client, its query client
/// and its reporter.
#[derive(Debug)]
pub struct ZipkinClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl ZipkinClient {
    /// Creates a client from a configuration.
    pub fn new(config: ZipkinConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("grow-zipkin-blocking")
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(ZipkinClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(ZipkinConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    fn query(&self) -> ZipkinQueryClient {
        self.inner.query()
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::server_info`].
    pub fn server_info(&self) -> Result<ZipkinServerInfo> {
        self.block_on(self.inner.server_info())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// See [`ZipkinReporter::report`](crate::ZipkinReporter::report) (default reporter).
    pub fn report(&self, spans: &[ZipkinSpan]) -> Result<()> {
        let reporter = self.inner.reporter();
        self.block_on(reporter.report(spans))
    }

    /// See [`ZipkinQueryClient::get_trace_spans`].
    pub fn get_trace_spans(&self, trace_id: &str) -> Result<Option<Vec<ZipkinSpan>>> {
        self.block_on(self.query().get_trace_spans(trace_id))
    }

    /// See [`ZipkinQueryClient::get_trace`].
    pub fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.block_on(self.query().get_trace(trace_id))
    }

    /// See [`ZipkinQueryClient::get_traces`].
    pub fn get_traces(&self, query: &ZipkinTraceQuery) -> Result<Vec<Vec<ZipkinSpan>>> {
        self.block_on(self.query().get_traces(query))
    }

    /// See [`ZipkinQueryClient::get_trace_many`].
    pub fn get_trace_many<S: AsRef<str>>(&self, trace_ids: &[S]) -> Result<Vec<Vec<ZipkinSpan>>> {
        self.block_on(self.query().get_trace_many(trace_ids))
    }

    /// See [`ZipkinQueryClient::search_traces`].
    pub fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        self.block_on(self.query().search_traces(query))
    }

    /// See [`ZipkinQueryClient::list_services`].
    pub fn list_services(&self) -> Result<Vec<String>> {
        self.block_on(self.query().list_services())
    }

    /// See [`ZipkinQueryClient::list_spans`].
    pub fn list_spans(&self, service_name: &str) -> Result<Vec<String>> {
        self.block_on(self.query().list_spans(service_name))
    }

    /// See [`ZipkinQueryClient::list_operations`].
    pub fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.block_on(self.query().list_operations(service))
    }

    /// See [`ZipkinQueryClient::list_remote_services`].
    pub fn list_remote_services(&self, service_name: &str) -> Result<Vec<String>> {
        self.block_on(self.query().list_remote_services(service_name))
    }

    /// See [`ZipkinQueryClient::get_dependencies`].
    pub fn get_dependencies(
        &self,
        opts: impl Into<ZipkinDependencyOptions>,
    ) -> Result<Vec<ZipkinDependencyLink>> {
        self.block_on(self.query().get_dependencies(opts))
    }

    /// See [`ZipkinQueryClient::autocomplete_keys`].
    pub fn autocomplete_keys(&self) -> Result<Vec<String>> {
        self.block_on(self.query().autocomplete_keys())
    }

    /// See [`ZipkinQueryClient::autocomplete_values`].
    pub fn autocomplete_values(&self, key: &str) -> Result<Vec<String>> {
        self.block_on(self.query().autocomplete_values(key))
    }

    /// See [`ZipkinQueryClient::wait_for_trace`].
    pub fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        self.block_on(self.query().wait_for_trace(trace_id, opts))
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for ZipkinClient {
    fn drop(&mut self) {
        self.inner.transport().close();
    }
}
