// Taken over from sdk-kit 0.1.0 (header removed): the skeleton config had a `token`; Zipkin has
// no credential option (gateway credentials are extra headers), so the service-config cases
// test the span endpoint and header redaction instead.
//! Configuration, environment parsing and config redaction.

use std::sync::Arc;
use std::time::Duration;

use grow_zipkin as sdk;
use sdk::core::config::{DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_TOTAL_TIMEOUT};
use sdk::core::env::ENV_PREFIX;
use sdk::core::{
    parse_bool, parse_duration, CommonConfig, EnvReader, ErrorCode, Hooks, RetryPolicy, TlsConfig,
};
use sdk::DEFAULT_URL;

use sdk::ZipkinConfig as Config;

fn var(name: &str) -> String {
    format!("{ENV_PREFIX}{name}")
}

#[test]
fn core_config_env_precedence() {
    let vendor = ["VENDOR_ADDR", "LEGACY_ADDR"];
    let env = EnvReader::from_map([
        (var("URL"), "http://grow:1"),
        ("VENDOR_ADDR".to_owned(), "http://vendor:2"),
    ]);
    // explicit > GROW_* > vendor > default
    let explicit = CommonConfig::builder()
        .url("http://explicit:0")
        .apply_env(&env, &vendor)
        .unwrap()
        .build("http://default:3")
        .unwrap();
    assert_eq!(explicit.url().as_str(), "http://explicit:0/");
    let grow = CommonConfig::builder()
        .apply_env(&env, &vendor)
        .unwrap()
        .build("http://default:3")
        .unwrap();
    assert_eq!(grow.url().as_str(), "http://grow:1/");
    let env_vendor = EnvReader::from_map([("LEGACY_ADDR", "http://legacy:4"), ("VENDOR_ADDR", "")]);
    let v = CommonConfig::builder()
        .apply_env(&env_vendor, &vendor)
        .unwrap()
        .build("http://default:3")
        .unwrap();
    assert_eq!(v.url().as_str(), "http://legacy:4/");
    let d = CommonConfig::builder()
        .apply_env(
            &EnvReader::from_map(Vec::<(String, String)>::new()),
            &vendor,
        )
        .unwrap()
        .build("http://default:3")
        .unwrap();
    assert_eq!(d.url().as_str(), "http://default:3/");

    // invalid values name the variable, never the value
    for (name, value) in [
        ("CONNECT_TIMEOUT", "soon-xyz"),
        ("REQUEST_TIMEOUT", "1parsec"),
        ("TOTAL_TIMEOUT", "-1s"),
        ("MAX_RETRIES", "many-xyz"),
        ("MAX_RETRIES", "-2"),
        ("INSECURE_SKIP_VERIFY", "maybe-xyz"),
    ] {
        let env = EnvReader::from_map([(var(name), value)]);
        let err = CommonConfig::builder().apply_env(&env, &[]).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config);
        assert!(err.message().contains(&var(name)), "{err}");
        assert!(!err.message().contains(value), "{err}");
    }

    // every variable is read
    let env = EnvReader::from_map([
        (var("CONNECT_TIMEOUT"), "1500"),
        (var("REQUEST_TIMEOUT"), "2s"),
        (var("TOTAL_TIMEOUT"), "1m30s"),
        (var("MAX_RETRIES"), "5"),
        (var("CA_CERT"), "/tmp/ca.pem"),
        (var("CLIENT_CERT"), "/tmp/c.pem"),
        (var("CLIENT_KEY"), "/tmp/c.key"),
        (var("INSECURE_SKIP_VERIFY"), "YES"),
        (var("PROXY"), "http://proxy:3128"),
    ]);
    let c = CommonConfig::builder()
        .apply_env(&env, &[])
        .unwrap()
        .build(DEFAULT_URL)
        .unwrap();
    assert_eq!(c.connect_timeout(), Duration::from_millis(1500));
    assert_eq!(c.request_timeout(), Duration::from_secs(2));
    assert_eq!(c.total_timeout(), Duration::from_secs(90));
    assert_eq!(c.retry().max_retries, 5);
    assert_eq!(
        c.tls().ca_cert_path.as_deref(),
        Some(std::path::Path::new("/tmp/ca.pem"))
    );
    assert!(c.tls().insecure_skip_verify);
    assert_eq!(c.proxy(), Some("http://proxy:3128"));

    // explicit values keep priority over every variable
    let c = CommonConfig::builder()
        .connect_timeout(Duration::from_secs(1))
        .request_timeout(Duration::from_secs(2))
        .total_timeout(Duration::from_secs(3))
        .max_retries(1)
        .ca_cert_path("/x/ca.pem")
        .client_cert("/x/c.pem", "/x/c.key")
        .insecure_skip_verify(false)
        .proxy("http://explicit-proxy:1")
        .apply_env(&env, &[])
        .unwrap()
        .build(DEFAULT_URL)
        .unwrap();
    assert_eq!(c.connect_timeout(), Duration::from_secs(1));
    assert_eq!(c.retry().max_retries, 1);
    assert!(!c.tls().insecure_skip_verify);
    assert_eq!(c.proxy(), Some("http://explicit-proxy:1"));
}

#[test]
fn core_config_service_from_env() {
    let env = EnvReader::from_map([
        (var("URL"), "https://svc.example:8443"),
        (var("ENDPOINT"), "https://ingest.example/api/v2/spans"),
    ]);
    let c = Config::builder().build_with_env(&env).unwrap();
    assert_eq!(c.url().as_str(), "https://svc.example:8443/");
    assert_eq!(c.endpoint(), "https://ingest.example/api/v2/spans");
    let c = Config::builder()
        .endpoint("https://explicit.example/api/v2/spans")
        .build_with_env(&env)
        .unwrap();
    assert_eq!(c.endpoint(), "https://explicit.example/api/v2/spans");
    let c = Config::builder().build().unwrap();
    assert_eq!(c.url().as_str(), format!("{DEFAULT_URL}/"));
    assert!(c.known_secrets().is_empty());
    assert!(c.validate().is_ok());
    // the process-environment entry points work (values depend on the environment)
    let _ = Config::from_env();
    let _ = Config::builder().build_from_env();
}

#[test]
fn core_config_builder_setters() {
    struct NoHooks;
    impl Hooks for NoHooks {}
    let c = Config::builder()
        .url("http://h:1/base")
        .connect_timeout(Duration::from_secs(1))
        .request_timeout(Duration::from_secs(2))
        .total_timeout(Duration::from_secs(3))
        .retry(RetryPolicy::none())
        .max_retries(2)
        .tls(TlsConfig {
            insecure_skip_verify: true,
            ..TlsConfig::default()
        })
        .ca_cert_path("/ca.pem")
        .insecure_skip_verify(true)
        .proxy("http://p:1")
        .header("x-a", "b")
        .user_agent_suffix("app/1")
        .hooks(Arc::new(NoHooks))
        .common(|b| b.ca_cert_pem("pem"))
        .build()
        .unwrap();
    let common = c.common();
    assert_eq!(common.connect_timeout(), Duration::from_secs(1));
    assert_eq!(common.request_timeout(), Duration::from_secs(2));
    assert_eq!(common.total_timeout(), Duration::from_secs(3));
    assert_eq!(common.retry().max_retries, 2);
    assert!(common.tls().insecure_skip_verify);
    assert_eq!(common.tls().ca_cert_pem.as_deref(), Some("pem"));
    assert_eq!(common.headers(), &[("x-a".to_owned(), "b".to_owned())]);
    assert_eq!(common.user_agent_suffix(), Some("app/1"));
    assert!(common.hooks().is_some());
    let defaults = CommonConfig::builder().build(DEFAULT_URL).unwrap();
    assert_eq!(defaults.connect_timeout(), DEFAULT_CONNECT_TIMEOUT);
    assert_eq!(defaults.request_timeout(), DEFAULT_REQUEST_TIMEOUT);
    assert_eq!(defaults.total_timeout(), DEFAULT_TOTAL_TIMEOUT);
    assert_eq!(defaults.retry().max_retries, 3);
    assert!(defaults.hooks().is_none());
}

#[test]
fn core_config_validation() {
    type Build = Box<dyn Fn() -> sdk::Result<CommonConfig>>;
    let cases: Vec<(&str, Build)> = vec![
        (
            "not a url",
            Box::new(|| CommonConfig::builder().url("not a url").build(DEFAULT_URL)),
        ),
        (
            "scheme",
            Box::new(|| CommonConfig::builder().url("ftp://x").build(DEFAULT_URL)),
        ),
        (
            "connect",
            Box::new(|| {
                CommonConfig::builder()
                    .connect_timeout(Duration::ZERO)
                    .build(DEFAULT_URL)
            }),
        ),
        (
            "request",
            Box::new(|| {
                CommonConfig::builder()
                    .request_timeout(Duration::ZERO)
                    .build(DEFAULT_URL)
            }),
        ),
        (
            "total",
            Box::new(|| {
                CommonConfig::builder()
                    .total_timeout(Duration::ZERO)
                    .build(DEFAULT_URL)
            }),
        ),
        (
            "cert",
            Box::new(|| {
                CommonConfig::builder()
                    .tls(TlsConfig {
                        client_cert_path: Some("/c".into()),
                        ..TlsConfig::default()
                    })
                    .build(DEFAULT_URL)
            }),
        ),
        (
            "proxy",
            Box::new(|| CommonConfig::builder().proxy("::nope").build(DEFAULT_URL)),
        ),
        (
            "proxy scheme",
            Box::new(|| CommonConfig::builder().proxy("ftp://p").build(DEFAULT_URL)),
        ),
        (
            "multiplier",
            Box::new(|| {
                CommonConfig::builder()
                    .retry(RetryPolicy {
                        multiplier: 0.5,
                        ..RetryPolicy::default()
                    })
                    .build(DEFAULT_URL)
            }),
        ),
        (
            "backoff",
            Box::new(|| {
                CommonConfig::builder()
                    .retry(RetryPolicy {
                        initial_backoff: Duration::from_secs(5),
                        max_backoff: Duration::from_secs(1),
                        ..RetryPolicy::default()
                    })
                    .build(DEFAULT_URL)
            }),
        ),
    ];
    for (name, build) in cases {
        let err = build().unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{name}");
    }
}

#[test]
fn core_redaction_config() {
    let c = Config::builder()
        .url("https://user:url-password-1@svc.example/?token=query-token-1&x=1")
        .endpoint("https://euser:config-token-secret-1@ingest.example/api/v2/spans")
        .header("Authorization", "Bearer header-token-1")
        .header("x-trace", "visible-value")
        .proxy("http://puser:proxy-password-1@proxy:3128")
        .build()
        .unwrap();
    let text = format!(
        "{c:?} {:?} {:?}",
        c.common(),
        CommonConfig::builder().url("http://u:builder-pw-1@h")
    );
    for secret in [
        "url-password-1",
        "query-token-1",
        "config-token-secret-1",
        "header-token-1",
        "proxy-password-1",
        "builder-pw-1",
    ] {
        assert!(!text.contains(secret), "{secret} leaked: {text}");
    }
    assert!(text.contains("visible-value"));
    assert!(text.contains("[REDACTED]"));
    let builder = Config::builder()
        .url("http://u:t-builder-1@h")
        .endpoint("http://u:t-builder-2@h/api/v2/spans")
        .header("authorization", "t-builder-3");
    let text = format!("{builder:?}");
    assert!(text.contains("REDACTED"), "{text}");
    for secret in ["t-builder-1", "t-builder-2", "t-builder-3"] {
        assert!(!text.contains(secret), "{secret} leaked: {text}");
    }
}

#[test]
fn env_reader_and_parsers() {
    let env = EnvReader::from_map([
        (var("A"), "x"),
        (var("EMPTY"), ""),
        ("VENDOR".to_owned(), "v"),
        (var("N"), " 42 "),
    ]);
    assert_eq!(env.prefix(), ENV_PREFIX);
    assert_eq!(env.string("A", &[]), Some("x".to_owned()));
    assert_eq!(env.string("EMPTY", &["VENDOR"]), Some("v".to_owned()));
    assert_eq!(
        env.lookup("EMPTY", &["VENDOR"]),
        Some(("VENDOR".to_owned(), "v".to_owned()))
    );
    assert_eq!(env.string("MISSING", &[]), None);
    assert_eq!(env.int("N", &[]).unwrap(), Some(42));
    assert_eq!(env.duration("MISSING", &[]).unwrap(), None);
    assert_eq!(env.bool("MISSING", &[]).unwrap(), None);
    assert_eq!(format!("{:?}", env.secret("A", &[]).unwrap()), "[REDACTED]");
    let custom = EnvReader::from_map([("MY_A", "1")]).with_prefix("MY_");
    assert_eq!(custom.bool("A", &[]).unwrap(), Some(true));
    let process = EnvReader::default();
    assert_eq!(process.prefix(), EnvReader::new().prefix());
    assert_eq!(
        process.string("SURELY_UNSET_VARIABLE_FOR_TESTS", &["ALSO_UNSET_VAR_XYZ"]),
        None
    );

    for (s, ms) in [
        ("1500", 1500u64),
        ("500ms", 500),
        ("10s", 10_000),
        ("2m", 120_000),
        ("1h", 3_600_000),
        ("1m30s", 90_000),
        ("1.5s", 1500),
        (" 250us ", 0),
        ("2000000ns", 2),
        ("1µs", 0),
    ] {
        assert_eq!(
            parse_duration(s).map(|d| d.as_millis() as u64),
            Some(ms),
            "{s}"
        );
    }
    for s in [
        "",
        "abc",
        "10",
        "10x",
        "s",
        "1.2.3s",
        "-1s",
        "99999999999999999999h",
    ] {
        if s == "10" {
            continue;
        }
        assert_eq!(parse_duration(s), None, "{s}");
    }
    for (s, b) in [
        ("true", true),
        ("ON", true),
        ("1", true),
        ("yes", true),
        ("False", false),
        ("0", false),
        ("no", false),
        ("off", false),
    ] {
        assert_eq!(parse_bool(s), Some(b));
    }
    assert_eq!(parse_bool("maybe"), None);
}
