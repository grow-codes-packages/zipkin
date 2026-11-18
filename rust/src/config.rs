// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `ZipkinConfig`: common settings (SPEC §2.2) plus the Zipkin credentials.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Hooks, Result, RetryPolicy, Secret, TlsConfig,
};

/// Endpoint used when neither the builder nor the environment sets one.
pub const DEFAULT_URL: &str = "http://127.0.0.1:9411";

/// Vendor variables checked (in order) after `GROW_ZIPKIN_URL`.
const VENDOR_URL_VARS: &[&str] = &[];
/// Vendor variables checked (in order) after `GROW_ZIPKIN_TOKEN`.
const VENDOR_TOKEN_VARS: &[&str] = &[];

/// Immutable client configuration; build it with [`ZipkinConfig::builder`] or
/// [`ZipkinConfig::from_env`]. `Debug` output redacts every credential.
#[derive(Debug, Clone)]
pub struct ZipkinConfig {
    common: CommonConfig,
    token: Option<Secret>,
}

impl ZipkinConfig {
    /// A builder with nothing set.
    pub fn builder() -> ZipkinConfigBuilder {
        ZipkinConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_ZIPKIN_*` > vendor variables > defaults).
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Endpoint base URL.
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// API token, when configured.
    pub fn token(&self) -> Option<&Secret> {
        self.token.as_ref()
    }

    /// Checks the service rules (common rules are checked when building).
    pub fn validate(&self) -> Result<()> {
        Ok(())
    }
}

/// Builder for [`ZipkinConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct ZipkinConfigBuilder {
    common: CommonConfigBuilder,
    token: Option<Secret>,
}

impl ZipkinConfigBuilder {
    /// Endpoint base URL (default [`DEFAULT_URL`]).
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.common = self.common.url(url);
        self
    }
    /// API token.
    pub fn token(mut self, token: impl Into<Secret>) -> Self {
        self.token = Some(token.into());
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
    /// Extra request header.
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
        let config = ZipkinConfig {
            common: self.common.build(DEFAULT_URL)?,
            token: self.token,
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<ZipkinConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds.
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<ZipkinConfig> {
        self.common = self.common.apply_env(env, VENDOR_URL_VARS)?;
        if self.token.is_none() {
            self.token = env.secret("TOKEN", VENDOR_TOKEN_VARS);
        }
        self.build()
    }
}
