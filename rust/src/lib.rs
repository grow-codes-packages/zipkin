// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-zipkin
//!
//! Rust SDK for [Zipkin](https://grow-codes-packages.github.io/zipkin/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_zipkin::Result<()> {
//! use grow_zipkin::tracing::{traced, TracingConfig};
//! use grow_zipkin::{ZipkinClient, ZipkinConfig, ZipkinSpan, ZipkinKind};
//!
//! // Export OpenTelemetry spans to Zipkin (v2 JSON to /api/v2/spans, W3C + B3 propagation).
//! let tracing = grow_zipkin::init_tracing(TracingConfig::new("checkout"))?;
//! traced("charge-card", async { Ok::<_, std::io::Error>(()) }).await.ok();
//! tracing.shutdown().await?;
//!
//! // Report spans without OpenTelemetry and read them back.
//! let client = ZipkinClient::new(ZipkinConfig::from_env()?)?;
//! let span = ZipkinSpan::new("5af7183fb1d4cf5f0a6665df1d0a9b8c", "0000000000000001")
//!     .with_kind(ZipkinKind::Server)
//!     .with_name("get /orders")
//!     .with_local_service("checkout")
//!     .with_timing(1_795_034_515_005_000, 1_000);
//! client.reporter().report(&[span]).await?;
//! let trace = client.query().get_trace("5af7183fb1d4cf5f0a6665df1d0a9b8c").await?;
//! println!("{} spans", trace.map_or(0, |t| t.spans.len()));
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Modules: [`ZipkinClient`] (health, ping, server version), [`ZipkinReporter`] (direct span
//! reporting), [`ZipkinQueryClient`] (the v2 query API and the category `TraceQueryClient`),
//! [`ZipkinSpanExporter`] (OpenTelemetry exporter, own implementation — the
//! `opentelemetry-zipkin` crate is discontinued), [`init_tracing`] (the Zipkin preset) and the
//! [`tracing`] category helpers.
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;
mod exporter;
mod model;
mod query;
mod reporter;
mod setup;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::{
    base_from_endpoint, spans_endpoint, DEFAULT_URL, ENV_OTEL_ZIPKIN_ENDPOINT, ENV_ZIPKIN_URL,
    SPANS_PATH,
};

pub use crate::client::{ZipkinClient, ZipkinServerInfo};

pub use crate::model::{
    annotation_to_event, dependencies_from_value, normalize_zipkin_span, strings_from_value,
    summarize_zipkin_trace, tag_value, zipkin_span_to_span, zipkin_spans_from_value,
    zipkin_trace_id, zipkin_trace_to_trace, zipkin_traces_from_value, ZipkinAnnotation,
    ZipkinDependencyLink, ZipkinEndpoint, ZipkinKind, ZipkinSpan,
};

pub use crate::reporter::{ZipkinReporter, ZipkinReporterOptions, DEFAULT_MAX_BATCH_SIZE};

pub use crate::query::{
    build_annotation_query, ZipkinDependencyOptions, ZipkinQueryClient, ZipkinTraceQuery,
    DEFAULT_SEARCH_LIMIT, DEFAULT_SEARCH_WINDOW, TRACE_MANY_CHUNK,
};

pub use crate::exporter::{
    attribute_to_tag, to_zipkin_span, ExportErrorHandler, ZipkinSpanExporter,
    ZipkinSpanExporterOptions, REMOTE_SERVICE_KEYS, UNKNOWN_SERVICE,
};

pub use crate::setup::{
    default_propagators, init_tracing, init_tracing_with_options, order_propagators,
    tracing_config_from_env, tracing_config_from_env_with, zipkin_exporter_factory, ZipkinExporter,
    ZipkinTracingOptions, DEFAULT_TRACING_PROTOCOL,
};

pub use crate::config::ZipkinConfig;

pub use crate::config::ZipkinConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type ZipkinError = Error;
