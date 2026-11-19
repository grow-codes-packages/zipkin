// Service code (Zipkin): ZipkinReporter — sends Zipkin v2 JSON spans to POST /api/v2/spans.
//! [`ZipkinReporter`]: reports spans without OpenTelemetry (`POST /api/v2/spans`, JSON v2).

use std::io::Write;

use crate::client::ZipkinClient;
use crate::config::{is_http_url, ZipkinConfig};
use crate::core::{Error, Result};
use crate::model::{normalize_zipkin_span, ZipkinSpan};
use crate::tracing::Compression;

/// Default number of spans per POST (SPEC batch default).
pub const DEFAULT_MAX_BATCH_SIZE: usize = 512;

const OPERATION: &str = "zipkin.report";

/// Options of [`ZipkinReporter`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZipkinReporterOptions {
    /// [`Compression::Gzip`] compresses each request body (`Content-Encoding: gzip`). Default
    /// [`Compression::None`].
    pub compression: Compression,
    /// Spans per POST; larger reports are split. Default 512.
    pub max_batch_size: usize,
    /// Ingest URL; default the config's [`endpoint`](ZipkinConfig::endpoint).
    pub endpoint: Option<String>,
}

impl Default for ZipkinReporterOptions {
    fn default() -> Self {
        ZipkinReporterOptions {
            compression: Compression::None,
            max_batch_size: DEFAULT_MAX_BATCH_SIZE,
            endpoint: None,
        }
    }
}

impl ZipkinReporterOptions {
    /// Sets the compression.
    pub fn compression(mut self, compression: Compression) -> Self {
        self.compression = compression;
        self
    }
    /// Sets the batch size.
    pub fn max_batch_size(mut self, max_batch_size: usize) -> Self {
        self.max_batch_size = max_batch_size;
        self
    }
    /// Sets the ingest URL.
    pub fn endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.endpoint = Some(endpoint.into());
        self
    }
}

/// Reports spans to Zipkin without OpenTelemetry: `POST /api/v2/spans` with a JSON array of v2
/// spans (`202 Accepted`). Spans are normalized locally first ([`normalize_zipkin_span`]), so an
/// invalid span fails with `VALIDATION` before anything is sent. Ingest is idempotent (Zipkin
/// merges spans by id), so 5xx, 429 and network errors are retried; a 400 or 413 is permanent.
///
/// Get one from [`ZipkinClient::reporter`] (shares the client's connections) or create it from
/// a config with [`ZipkinReporter::new`] (owns its connections; call
/// [`close`](Self::close)). Cheap to clone.
#[derive(Debug, Clone)]
pub struct ZipkinReporter {
    client: ZipkinClient,
    owned: bool,
    endpoint: String,
    compression: Compression,
    max_batch_size: usize,
}

impl ZipkinReporter {
    /// A reporter with its own connections.
    pub fn new(config: ZipkinConfig, options: ZipkinReporterOptions) -> Result<Self> {
        Self::build(ZipkinClient::new(config)?, true, options)
    }

    /// A standalone reporter from `GROW_ZIPKIN_*` / `ZIPKIN_URL` /
    /// `OTEL_EXPORTER_ZIPKIN_ENDPOINT`.
    pub fn from_env(options: ZipkinReporterOptions) -> Result<Self> {
        Self::new(ZipkinConfig::from_env()?, options)
    }

    pub(crate) fn build(
        client: ZipkinClient,
        owned: bool,
        options: ZipkinReporterOptions,
    ) -> Result<Self> {
        if options.max_batch_size == 0 {
            return Err(Error::config(
                "zipkin.reporter: maxBatchSize must be a positive integer",
            ));
        }
        let endpoint = options
            .endpoint
            .unwrap_or_else(|| client.config().endpoint().to_owned());
        if !is_http_url(&endpoint) {
            return Err(Error::config(
                "zipkin.reporter: endpoint must be an http or https URL",
            ));
        }
        Ok(ZipkinReporter {
            client,
            owned,
            endpoint,
            compression: options.compression,
            max_batch_size: options.max_batch_size,
        })
    }

    /// Ingest URL used by [`report`](Self::report).
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// Request body compression.
    pub fn compression(&self) -> Compression {
        self.compression
    }

    /// Spans per POST.
    pub fn max_batch_size(&self) -> usize {
        self.max_batch_size
    }

    /// Sends spans: an empty list sends nothing; more than `max_batch_size` spans are sent in
    /// several POSTs, in order. Spans are queryable once the call returns (immediately with
    /// in-memory storage; persistent backends may lag — use `wait_for_trace`).
    pub async fn report(&self, spans: &[ZipkinSpan]) -> Result<()> {
        let normalized = spans
            .iter()
            .map(|s| normalize_zipkin_span(s, OPERATION))
            .collect::<Result<Vec<_>>>()?;
        for batch in normalized.chunks(self.max_batch_size) {
            let json = serde_json::to_vec(batch)?;
            let mut req = self
                .client
                .transport()
                .post(&self.endpoint)
                .operation(OPERATION)
                .idempotent(true);
            req = match self.compression {
                Compression::Gzip => req
                    .body(gzip(&json), "application/json")
                    .header("content-encoding", "gzip"),
                Compression::None => req.body(json, "application/json"),
            };
            req.send().await?;
        }
        Ok(())
    }

    /// Releases connections when this reporter owns them (no-op for
    /// [`ZipkinClient::reporter`]). Idempotent.
    pub async fn close(&self) {
        if self.owned {
            self.client.close().await;
        }
    }

    pub(crate) fn close_now(&self) {
        if self.owned {
            self.client.transport().close();
        }
    }
}

pub(crate) fn gzip(data: &[u8]) -> Vec<u8> {
    let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    enc.write_all(data)
        .and_then(|_| enc.finish())
        .expect("writing to a Vec cannot fail")
}
