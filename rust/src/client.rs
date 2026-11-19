// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `ZipkinClient`: entry point of the SDK (SPEC §2.4) — health, ping and version of a Zipkin
//! server plus the query client, the span reporter and the tracing preset.

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde_json::Value;

use crate::config::ZipkinConfig;
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, HealthStatus, HttpTransport, Result};
use crate::query::ZipkinQueryClient;
use crate::reporter::{ZipkinReporter, ZipkinReporterOptions};
use crate::setup::{init_tracing_with_options, resolve_protocol, ZipkinTracingOptions};
use crate::tracing::{Protocol, TracingConfig, TracingHandle};

const HEALTHY_WORDS: &[&str] = &[
    "ok",
    "up",
    "healthy",
    "pass",
    "passing",
    "green",
    "ready",
    "available",
    "running",
    "serving",
];

/// Build information of the server (`GET /info`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ZipkinServerInfo {
    /// e.g. `3.6.1`.
    pub version: Option<String>,
    /// Short git commit.
    pub commit: Option<String>,
}

/// Async client for Zipkin (port 9411): health, ping and server version, plus the query API
/// ([`query`](Self::query)), the direct span reporter ([`reporter`](Self::reporter)) and
/// tracing setup ([`init_tracing`](Self::init_tracing)). Cheap to clone (clones share one
/// connection pool). [`close`](Self::close) also shuts down every tracing handle created
/// through this client.
#[derive(Debug, Clone)]
pub struct ZipkinClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    config: ZipkinConfig,
    transport: HttpTransport,
    on_close: CloseGroup,
}

impl ZipkinClient {
    /// Creates a client from a configuration.
    pub fn new(config: ZipkinConfig) -> Result<Self> {
        config.validate()?;
        let transport = HttpTransport::new(config.common())?;
        for secret in config.known_secrets() {
            transport.add_known_secret(&secret);
        }
        Ok(ZipkinClient {
            inner: Arc::new(Inner {
                config,
                transport,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(ZipkinConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &ZipkinConfig {
        &self.inner.config
    }

    /// The HTTP transport (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// Registers work that [`ZipkinClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// The trace query client (shares this client's connections).
    pub fn query(&self) -> ZipkinQueryClient {
        ZipkinQueryClient::shared(self.clone())
    }

    /// The span reporter (shares this client's connections; no compression, 512 spans per
    /// POST).
    pub fn reporter(&self) -> ZipkinReporter {
        self.create_reporter(ZipkinReporterOptions::default())
            .expect("default reporter options are valid")
    }

    /// A reporter with other options that shares this client's connections.
    pub fn create_reporter(&self, options: ZipkinReporterOptions) -> Result<ZipkinReporter> {
        ZipkinReporter::build(self.clone(), false, options)
    }

    /// Liveness: `GET /health` → `{"status":"UP"}`. A 503 (`DOWN`, storage unavailable) gives
    /// `healthy: false`. Never fails for an unhealthy or unreachable service; only `CANCELLED`
    /// and `CONFIG` are returned as errors.
    pub async fn health(&self) -> Result<HealthStatus> {
        let resp = self
            .transport()
            .get("/health")
            .operation("health")
            .ok_status(503)
            .send()
            .await;
        let resp = match resp {
            Ok(resp) => resp,
            Err(err) => return HealthStatus::from_error(err),
        };
        if resp.status() == 503 {
            return Ok(HealthStatus::unhealthy("DOWN").with_detail("httpStatus", "503"));
        }
        let body = resp.json::<Value>().unwrap_or(Value::Null);
        let word = body
            .get("status")
            .and_then(Value::as_str)
            .unwrap_or("UP")
            .to_owned();
        let healthy = HEALTHY_WORDS.contains(&word.trim().to_ascii_lowercase().as_str());
        let mut status = if healthy {
            HealthStatus::healthy(word)
        } else {
            HealthStatus::unhealthy(word)
        };
        if let Some(zipkin) = body.get("zipkin") {
            if let Some(s) = zipkin.get("status").and_then(Value::as_str) {
                status = status.with_detail("zipkin", s);
            }
            if let Some(d) = zipkin.get("details") {
                status = status.with_detail("storage", d.to_string());
            }
        }
        Ok(status)
    }

    /// Round trip through the query API and storage: `GET /api/v2/services` (through a gateway
    /// this also proves the auth headers). Returns the latency.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        self.transport()
            .get("/api/v2/services")
            .operation("ping")
            .send()
            .await?;
        Ok(started.elapsed())
    }

    /// Build information: `GET /info` → `{"zipkin":{"version","commit"}}` (empty when the
    /// server or a gateway answers 404).
    pub async fn server_info(&self) -> Result<ZipkinServerInfo> {
        let resp = self
            .transport()
            .get("/info")
            .operation("serverVersion")
            .ok_status(404)
            .send()
            .await?;
        if resp.status() == 404 {
            return Ok(ZipkinServerInfo::default());
        }
        let body = resp.json::<Value>().unwrap_or(Value::Null);
        let field = |name: &str| {
            body.get("zipkin")
                .and_then(|z| z.get(name))
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
        };
        Ok(ZipkinServerInfo {
            version: field("version"),
            commit: field("commit"),
        })
    }

    /// Server version (e.g. `3.6.1`) from `GET /info`; `UNSUPPORTED` when the server (or a
    /// gateway) does not report one.
    pub async fn server_version(&self) -> Result<String> {
        self.server_info().await?.version.ok_or_else(|| {
            Error::unsupported("serverVersion: the service does not report a version")
        })
    }

    /// Starts OpenTelemetry tracing to this Zipkin (see [`crate::init_tracing`]). The native
    /// exporter uses this client's endpoint, headers, TLS, proxy, retries and hooks; an explicit
    /// `config.endpoint` wins. The handle is shut down by [`close`](Self::close).
    pub fn init_tracing(&self, config: TracingConfig) -> Result<TracingHandle> {
        self.init_tracing_with_options(config, ZipkinTracingOptions::default())
    }

    /// [`init_tracing`](Self::init_tracing) with options (the client config always wins over
    /// `options.client_config`).
    pub fn init_tracing_with_options(
        &self,
        mut config: TracingConfig,
        options: ZipkinTracingOptions,
    ) -> Result<TracingHandle> {
        if self.is_closed() {
            return Err(Error::config("initTracing: client is closed"));
        }
        let protocol = resolve_protocol(
            config.protocol,
            &options.env_reader(),
            options.protocol_override(),
        )?;
        if protocol == Protocol::Native && config.endpoint.is_none() {
            config.endpoint = Some(self.config().endpoint().to_owned());
        }
        let handle =
            init_tracing_with_options(config, options.client_config(self.config().clone()))?;
        let shutdown = handle.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = shutdown.shutdown().await;
            })
        });
        Ok(handle)
    }

    /// Shuts down the tracing handles created through this client (flushing their batch
    /// processors), runs other close work, then closes the transport. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.transport.close();
    }

    /// Whether [`ZipkinClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed()
    }
}
