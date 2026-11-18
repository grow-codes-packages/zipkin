// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `ZipkinClient`: entry point of the SDK (SPEC §2.4).

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde::Deserialize;

use crate::config::ZipkinConfig;
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, HealthStatus, HttpTransport, Result};

/// Async client for Zipkin. Cheap to clone (clones share one connection pool).
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

#[derive(Deserialize)]
struct StatusBody {
    status: Option<String>,
}

#[derive(Deserialize)]
struct VersionBody {
    version: Option<String>,
}

impl ZipkinClient {
    /// Creates a client from a configuration.
    pub fn new(config: ZipkinConfig) -> Result<Self> {
        config.validate()?;
        let transport = HttpTransport::new(config.common())?;
        if let Some(token) = config.token() {
            transport.add_known_secret(token);
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

    /// Unauthenticated liveness check. Never fails for an unhealthy or unreachable service;
    /// only `CANCELLED` and `CONFIG` are returned as errors.
    pub async fn health(&self) -> Result<HealthStatus> {
        let resp = self
            .transport()
            .get("/health")
            .operation("health")
            .ok_status(500)
            .ok_status(503)
            .send()
            .await;
        let resp = match resp {
            Ok(resp) => resp,
            Err(err) => return HealthStatus::from_error(err),
        };
        let word = resp.json::<StatusBody>().ok().and_then(|b| b.status);
        Ok(if resp.status() < 300 {
            HealthStatus::healthy(word.unwrap_or_else(|| "ok".to_owned()))
        } else {
            HealthStatus::unhealthy(word.unwrap_or_else(|| "unhealthy".to_owned()))
                .with_detail("httpStatus", resp.status().to_string())
        })
    }

    /// Authenticated round trip; returns the latency. `AUTHENTICATION` on bad credentials.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        let mut req = self.transport().get("/v1/ping").operation("ping");
        if let Some(token) = self.config().token() {
            req = req.bearer(token);
        }
        req.send().await?;
        Ok(started.elapsed())
    }

    /// Server version string; `UNSUPPORTED` when the service exposes none.
    pub async fn server_version(&self) -> Result<String> {
        let resp = self
            .transport()
            .get("/version")
            .operation("serverVersion")
            .send()
            .await
            .map_err(|e| match e.http_status() {
                Some(404) => Error::unsupported("serverVersion: the service exposes no version"),
                _ => e,
            })?;
        resp.json::<VersionBody>()?
            .version
            .ok_or_else(|| Error::unsupported("serverVersion: the service exposes no version"))
    }

    /// Flushes and stops background work, then closes the transport. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.transport.close();
    }

    /// Whether [`ZipkinClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed()
    }
}
