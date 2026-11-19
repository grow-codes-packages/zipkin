// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `ZipkinConfig`: common settings (SPEC §2.2) plus the span ingest endpoint.

use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::core::redact::{is_sensitive_header, redact_url};
use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};

/// Endpoint used when neither the builder nor the environment sets one.
pub const DEFAULT_URL: &str = "http://127.0.0.1:9411";

/// Path of the Zipkin v2 span ingest API.
pub const SPANS_PATH: &str = "/api/v2/spans";

/// Vendor variable checked after `GROW_ZIPKIN_URL`.
pub const ENV_ZIPKIN_URL: &str = "ZIPKIN_URL";
/// OpenTelemetry's Zipkin exporter endpoint (a full spans URL).
pub const ENV_OTEL_ZIPKIN_ENDPOINT: &str = "OTEL_EXPORTER_ZIPKIN_ENDPOINT";

/// Vendor variables checked (in order) after `GROW_ZIPKIN_URL`.
const VENDOR_URL_VARS: &[&str] = &[ENV_ZIPKIN_URL];

/// `<base>/api/v2/spans`, appended once: a base that already ends with the spans path is kept, a
/// gateway prefix is preserved and trailing slashes are dropped.
pub fn spans_endpoint(base: &str) -> String {
    let trimmed = base.trim_end_matches('/');
    if trimmed.ends_with(SPANS_PATH) {
        trimmed.to_owned()
    } else {
        format!("{trimmed}{SPANS_PATH}")
    }
}

/// The server base URL of a spans endpoint: strips one trailing `/api/v2/spans` (and slashes).
pub fn base_from_endpoint(endpoint: &str) -> String {
    let trimmed = endpoint.trim_end_matches('/');
    trimmed
        .strip_suffix(SPANS_PATH)
        .unwrap_or(trimmed)
        .to_owned()
}

pub(crate) fn is_http_url(value: &str) -> bool {
    url::Url::parse(value)
        .map(|u| matches!(u.scheme(), "http" | "https") && u.has_host())
        .unwrap_or(false)
}

/// Immutable client configuration; build it with [`ZipkinConfig::builder`] or
/// [`ZipkinConfig::from_env`].
///
/// Stock Zipkin has no authentication: deployments behind a gateway pass credentials as extra
/// [`headers`](ZipkinConfigBuilder::header), which are redacted in `Debug` output, errors, hooks
/// and logs.
#[derive(Clone)]
pub struct ZipkinConfig {
    common: CommonConfig,
    endpoint: String,
}

impl fmt::Debug for ZipkinConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ZipkinConfig")
            .field("common", &self.common)
            .field("endpoint", &redact_url(&self.endpoint))
            .finish()
    }
}

impl ZipkinConfig {
    /// A builder with nothing set.
    pub fn builder() -> ZipkinConfigBuilder {
        ZipkinConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_ZIPKIN_*` > vendor variables > defaults); see
    /// [`ZipkinConfigBuilder::build_with_env`].
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Server base URL (port 9411; a gateway path prefix is kept).
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// Span ingest URL used by the reporter and the tracing exporter
    /// (`<url>/api/v2/spans` unless set).
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// Values of sensitive extra headers (`Authorization`, API keys, …) that must never appear
    /// in errors, hooks or logs.
    pub fn known_secrets(&self) -> Vec<Secret> {
        self.common
            .headers()
            .iter()
            .filter(|(name, value)| is_sensitive_header(name) && !value.is_empty())
            .map(|(_, value)| Secret::new(value.clone()))
            .collect()
    }

    /// Checks the service rules (common rules are checked when building): the endpoint must
    /// be an absolute `http`/`https` URL.
    pub fn validate(&self) -> Result<()> {
        if !is_http_url(&self.endpoint) {
            return Err(Error::config(
                "endpoint must be an absolute http or https URL",
            ));
        }
        Ok(())
    }
}

/// Builder for [`ZipkinConfig`]. Values set here win over the environment.
#[derive(Clone, Default)]
pub struct ZipkinConfigBuilder {
    common: CommonConfigBuilder,
    url_set: bool,
    endpoint: Option<String>,
}

impl fmt::Debug for ZipkinConfigBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ZipkinConfigBuilder")
            .field("common", &self.common)
            .field("endpoint", &self.endpoint.as_deref().map(redact_url))
            .finish()
    }
}

impl ZipkinConfigBuilder {
    /// Server base URL (default [`DEFAULT_URL`]). A gateway path prefix is kept
    /// (`https://gw.example/zipkin` → `https://gw.example/zipkin/api/v2/...`).
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.common = self.common.url(url);
        self.url_set = true;
        self
    }
    /// Full span ingest URL (default `<url>/api/v2/spans`).
    pub fn endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.endpoint = Some(endpoint.into());
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header, sent with every query, report and export (e.g. the
    /// `Authorization` header of a gateway; sensitive values are redacted).
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment.
    pub fn build(self) -> Result<ZipkinConfig> {
        let common = self.common.build(DEFAULT_URL)?;
        let endpoint = match self.endpoint {
            Some(e) if !e.trim().is_empty() => e.trim().to_owned(),
            _ => spans_endpoint(common.url().as_str()),
        };
        let config = ZipkinConfig { common, endpoint };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<ZipkinConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds.
    ///
    /// - url: `GROW_ZIPKIN_URL` > `ZIPKIN_URL` > `OTEL_EXPORTER_ZIPKIN_ENDPOINT` without its
    ///   trailing `/api/v2/spans` > [`DEFAULT_URL`].
    /// - endpoint: `GROW_ZIPKIN_ENDPOINT` > `OTEL_EXPORTER_ZIPKIN_ENDPOINT` (verbatim) >
    ///   `<url>/api/v2/spans`.
    /// - timeouts, retries, TLS and proxy: the common `GROW_ZIPKIN_*` variables (SPEC §2.2).
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<ZipkinConfig> {
        if !self.url_set {
            let url = env.string("URL", VENDOR_URL_VARS).or_else(|| {
                env.lookup("", &[ENV_OTEL_ZIPKIN_ENDPOINT])
                    .map(|(_, v)| base_from_endpoint(v.trim()))
            });
            if let Some(url) = url {
                self.common = self.common.url(url.trim());
                self.url_set = true;
            }
        }
        self.common = self.common.apply_env(env, VENDOR_URL_VARS)?;
        if self.endpoint.is_none() {
            self.endpoint = env
                .string("ENDPOINT", &[ENV_OTEL_ZIPKIN_ENDPOINT])
                .map(|v| v.trim().to_owned());
        }
        self.build()
    }
}
