// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-zipkin
//!
//! Rust SDK for [Zipkin](https://grow-codes-packages.github.io/zipkin/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_zipkin::Result<()> {
//! use grow_zipkin::{ZipkinClient, ZipkinConfig};
//!
//! let client = ZipkinClient::new(ZipkinConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::ZipkinClient;

pub use crate::config::ZipkinConfig;

pub use crate::config::ZipkinConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type ZipkinError = Error;
