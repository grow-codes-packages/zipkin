// Taken over from sdk-kit 0.1.0 (header removed): `core.health` ran against the demo service's
// routes (/health, /v1/ping, /version with a token); the mock now serves Zipkin's routes
// (/health, /api/v2/services, /info) and the config has no token.
//! Conformance runner: variables, expectations, skips, report, and `core.health` against a mock.
#![cfg(feature = "testing")]

mod common;

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use common::fast_retry;
use grow_zipkin as sdk;
use sdk::core::{Error, ErrorCode, Result};
use sdk::testing::{
    check_expectation, json_matches, load_scenarios, run_id, scenarios_dir, CategoryHandler,
    ConformanceRunner, CoreHandler, Scenario, ScenarioSession, ScenarioStatus, StepOutput, Vars,
};
use serde_json::{json, Value};
use wiremock::matchers::{header, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use sdk::ZipkinClient as Client;
use sdk::ZipkinConfig as Config;

fn dir() -> std::path::PathBuf {
    scenarios_dir(env!("CARGO_MANIFEST_DIR"))
}

#[test]
fn vars_substitute_values_and_keys() {
    let mut vars = Vars::new();
    vars.set("run", "abc12345");
    vars.set("traceId", "t-1");
    assert_eq!(vars.get("run"), Some("abc12345"));
    assert_eq!(vars.substitute("x-${run}-${traceId}"), "x-abc12345-t-1");
    assert_eq!(vars.substitute("${unknown} ${run"), "${unknown} ${run");
    assert_eq!(vars.substitute("#{i} plain"), "#{i} plain");
    let v = vars.substitute_value(&json!({
        "conformance-root-${run}": ["child-${run}"],
        "n": 1,
        "nested": {"${traceId}": [true, null, "${run}"]}
    }));
    assert_eq!(
        v,
        json!({"conformance-root-abc12345": ["child-abc12345"], "n": 1, "nested": {"t-1": [true, null, "abc12345"]}})
    );
    let id = run_id();
    assert_eq!(id.len(), 8);
    assert!(id
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit()));
}

fn ok(value: Value) -> Result<StepOutput> {
    Ok(StepOutput::json(value))
}

#[test]
fn expectations() {
    let pass = |e: Value, o: Result<StepOutput>| check_expectation(&e, &o).unwrap();
    let fail = |e: Value, o: Result<StepOutput>| check_expectation(&e, &o).unwrap_err();

    pass(
        json!({"equals": {"healthy": true}}),
        ok(json!({"healthy": true, "status": "ok", "details": {}})),
    );
    pass(json!({"equals": 42}), ok(json!(42.0)));
    pass(
        json!({"equals": [1, {"a": 1}]}),
        ok(json!([1, {"a": 1, "b": 2}])),
    );
    fail(json!({"equals": [1]}), ok(json!([1, 2])));
    fail(json!({"equals": {"x": 1}}), ok(json!({})));
    pass(json!({"null": true}), ok(Value::Null));
    fail(json!({"null": true}), ok(json!(1)));
    pass(json!({"notNull": true}), ok(json!("x")));
    pass(json!({"true": true}), ok(json!(true)));
    pass(json!({"false": true}), ok(json!(false)));
    fail(json!({"false": true}), ok(json!(true)));
    pass(json!({"contains": "svc"}), ok(json!(["a", "svc"])));
    pass(json!({"contains": "ell"}), ok(json!("hello")));
    pass(json!({"notContains": "z"}), ok(json!(["a"])));
    assert!(fail(json!({"contains": 1}), ok(json!(1))).contains("contains"));
    pass(json!({"count": 2}), ok(json!({"a": 1, "b": 2})));
    pass(json!({"count": 3}), ok(json!([1, 2, 3])));
    pass(
        json!({"count": 1}),
        ok(json!({"traceId": "t", "spans": [{}]})),
    );
    pass(json!({"count": 5}), Ok(StepOutput::none().with_count(5)));
    assert!(fail(json!({"count": 1}), ok(json!("s"))).contains("no count"));
    pass(json!({"minCount": 2}), ok(json!([1, 2, 3])));
    fail(json!({"minCount": 4}), ok(json!([1, 2, 3])));
    pass(
        json!({"sorted": true}),
        ok(json!([1, 2, {"name": "a"}, {"name": "b"}, "c", {"x": 1}])),
    );
    fail(json!({"sorted": true}), ok(json!(["b", "a"])));
    fail(json!({"sorted": true}), ok(json!("x")));
    for (t, v) in [
        ("string", json!("s")),
        ("number", json!(1)),
        ("boolean", json!(true)),
        ("object", json!({})),
        ("array", json!([])),
        ("null", Value::Null),
    ] {
        pass(json!({"type": t}), ok(v));
    }
    fail(json!({"type": "string"}), ok(json!(1)));
    let secret = Ok(StepOutput::json(json!("SecretValue([REDACTED])"))
        .with_display("SecretValue([REDACTED])")
        .with_reveal("s3cr3t"));
    pass(
        json!({"reveal": "s3cr3t", "stringForm": {"notContains": "s3cr3t", "contains": "REDACTED"}}),
        secret.clone(),
    );
    fail(json!({"reveal": "other"}), secret.clone());
    fail(json!({"reveal": 1}), secret.clone());
    fail(
        json!({"stringForm": {"notContains": "SecretValue"}}),
        secret.clone(),
    );
    fail(json!({"stringForm": {"contains": "nope"}}), secret.clone());
    fail(json!({"stringForm": "x"}), secret);
    pass(json!({"error": "NOT_FOUND"}), Err(Error::not_found("x")));
    assert!(
        fail(json!({"error": "NOT_FOUND"}), Err(Error::validation("x"))).contains("VALIDATION")
    );
    assert!(fail(json!({"error": "NOT_FOUND"}), ok(json!(1))).contains("success"));
    fail(json!({"error": 1}), ok(json!(1)));
    assert!(
        fail(json!({"equals": 1}), Err(Error::timeout("t"))).contains("unexpected error TIMEOUT")
    );
    assert!(fail(json!({"bogus": 1}), ok(json!(1))).contains("unknown expectation"));
    fail(json!([1]), ok(json!(1)));

    let trace = json!({"spans": [
        {"name": "root", "spanId": "1"},
        {"name": "child-a", "spanId": "2", "parentSpanId": "1"},
        {"name": "child-b", "spanId": "3", "parentSpanId": "1"},
        {"name": "grandchild", "spanId": "4", "parentSpanId": "3"}
    ]});
    pass(
        json!({"tree": {"root": ["child-b", "child-a"], "child-b": ["grandchild"]}}),
        ok(trace.clone()),
    );
    assert!(
        fail(json!({"tree": {"root": ["child-a"]}}), ok(trace.clone())).contains("children of")
    );
    fail(json!({"tree": ["x"]}), ok(trace.clone()));
    fail(json!({"tree": {"root": "x"}}), ok(trace));
    fail(json!({"tree": {}}), ok(json!({})));

    assert!(json_matches(&json!("a"), &json!("a")));
    assert!(!json_matches(&json!(1), &json!("1")));
}

/// A fake category used to exercise the runner itself.
struct Fake {
    log: Arc<Mutex<Vec<String>>>,
    fail_open: bool,
}

struct FakeSession {
    log: Arc<Mutex<Vec<String>>>,
}

#[async_trait]
impl CategoryHandler for Fake {
    fn category(&self) -> &str {
        "fake"
    }
    async fn open(&self, scenario: &Scenario, vars: &Vars) -> Result<Box<dyn ScenarioSession>> {
        if self.fail_open {
            return Err(Error::unavailable("cannot connect"));
        }
        self.log.lock().unwrap().push(format!(
            "open {} {:?} {}",
            scenario.id,
            scenario.batch_options,
            vars.get("prefix").unwrap()
        ));
        Ok(Box::new(FakeSession {
            log: self.log.clone(),
        }))
    }
}

#[async_trait]
impl ScenarioSession for FakeSession {
    async fn run(&mut self, op: &str, args: &Value, _vars: &Vars) -> Result<StepOutput> {
        self.log.lock().unwrap().push(format!("{op} {args}"));
        match op {
            "echo" => Ok(StepOutput::json(
                args.get("v").cloned().unwrap_or(Value::Null),
            )),
            "fail" => Err(Error::conflict("nope")),
            _ => Err(Error::unsupported(op.to_owned())),
        }
    }
    async fn close(&mut self) {
        self.log.lock().unwrap().push("close".into());
    }
}

fn scenario(value: Value) -> Scenario {
    serde_json::from_value(value).unwrap()
}

#[tokio::test]
async fn runner_flow_and_report() {
    let log = Arc::new(Mutex::new(Vec::new()));
    let scenarios = vec![
        scenario(
            json!({"id": "fake.pass", "category": "fake", "batchOptions": {"maxBatchSize": 1}, "steps": [
                {"op": "echo", "args": {"v": "x-${run}"}, "save": "first"},
                {"op": "echo", "args": {"v": {"k": "${first}", "${serviceName}": 1}}, "save": "obj",
                 "expect": {"equals": {"svc-1": 1}}},
                {"op": "echo", "args": {"v": "${obj}"}, "expect": {"contains": "svc-1"}},
                {"op": "fail", "expect": {"error": "CONFLICT"}},
                {"op": "echo", "requires": ["exotic"]}
            ]}),
        ),
        scenario(json!({"id": "fake.fail", "category": "fake", "steps": [
            {"op": "fail"},
            {"op": "echo"}
        ]})),
        scenario(json!({"id": "fake.expect", "category": "fake", "steps": [
            {"op": "echo", "args": {"v": 1}, "expect": {"equals": 2}}
        ]})),
        scenario(
            json!({"id": "fake.skip", "category": "fake", "requires": ["write", "exotic"], "steps": []}),
        ),
        scenario(json!({"id": "other.ignored", "category": "other", "steps": []})),
    ];
    let runner = ConformanceRunner::new("/unused")
        .capabilities(["write"])
        .prefix("p/${run}/")
        .var("serviceName", "svc-1")
        .handler(Fake {
            log: log.clone(),
            fail_open: false,
        });
    assert!(format!("{runner:?}").contains("fake"));
    let report = runner.run_scenarios(&scenarios).await;
    assert_eq!(
        (report.passed(), report.failed(), report.skipped()),
        (1, 2, 1)
    );
    assert!(!report.is_success());
    assert_eq!(
        report.results.len(),
        4,
        "categories without a handler are not reported"
    );
    let pass = report.result("fake.pass").unwrap();
    assert_eq!(pass.status, ScenarioStatus::Passed);
    assert_eq!(
        pass.skipped_steps,
        vec!["4:echo (missing: exotic)".to_owned()]
    );
    match &report.result("fake.fail").unwrap().status {
        ScenarioStatus::Failed(m) => assert!(
            m.contains("step 0 (fail): unexpected error CONFLICT"),
            "{m}"
        ),
        other => panic!("{other:?}"),
    }
    match &report.result("fake.expect").unwrap().status {
        ScenarioStatus::Failed(m) => assert!(m.contains("expectation equals"), "{m}"),
        other => panic!("{other:?}"),
    }
    assert_eq!(
        report.result("fake.skip").unwrap().status,
        ScenarioStatus::Skipped("missing capabilities: exotic".into())
    );
    let text = report.to_string();
    assert!(
        text.contains("PASS fake.pass")
            && text.contains("FAIL fake.fail")
            && text.contains("SKIP fake.skip")
    );
    assert!(text.contains("skipped step 4:echo"));
    let j = report.to_json();
    assert_eq!(j["failed"], 2);
    assert_eq!(j["results"][0]["status"], "passed");
    let log = log.lock().unwrap().clone();
    assert!(log[0].starts_with("open fake.pass Some(Object {\"maxBatchSize\": Number(1)}) p/"));
    assert!(log.iter().any(|l| l.contains("\"svc-1\":1")));
    assert_eq!(log.iter().filter(|l| *l == "close").count(), 3);
    let only = ConformanceRunner::new("/unused")
        .handler(Fake {
            log: Arc::new(Mutex::new(Vec::new())),
            fail_open: true,
        })
        .only(["fake.expect"]);
    let report = only.run_scenarios(&scenarios).await;
    assert_eq!(report.results.len(), 1);
    match &report.results[0].status {
        ScenarioStatus::Failed(m) => assert!(m.contains("setup failed")),
        other => panic!("{other:?}"),
    }
    let result = std::panic::catch_unwind(|| report.assert_success());
    assert!(result.is_err());
}

#[test]
fn load_scenarios_files() {
    let scenarios = load_scenarios(dir()).expect("scenario dir");
    assert!(scenarios.iter().any(|s| s.id == "core.health"));
    assert!(scenarios.windows(2).all(|w| w[0].id <= w[1].id));
    let err = load_scenarios("/nonexistent/scenarios").unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    let tmp = std::env::temp_dir().join(format!("grow-sdk-scenarios-{}", std::process::id()));
    std::fs::create_dir_all(&tmp).unwrap();
    std::fs::write(tmp.join("notes.txt"), "ignored").unwrap();
    std::fs::write(tmp.join("bad.json"), "{").unwrap();
    let err = load_scenarios(&tmp).unwrap_err();
    assert!(err.message().contains("is invalid"));
    std::fs::remove_dir_all(&tmp).unwrap();
}

#[tokio::test]
async fn conformance_core_against_mock_demo() {
    let server = MockServer::start().await;
    Mock::given(path("/health"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status": "UP"})))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/services"))
        .and(header("x-gateway-key", "demo-token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!(["grow-frontend"])))
        .mount(&server)
        .await;
    Mock::given(path("/info"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"zipkin": {"version": "3.6.1", "commit": "b2b0c98"}})),
        )
        .mount(&server)
        .await;
    let url = server.uri();
    let factory = move || {
        Client::new(
            Config::builder()
                .url(url.clone())
                .header("x-gateway-key", "demo-token")
                .retry(fast_retry(0.0))
                .build()?,
        )
    };
    let report = ConformanceRunner::new(dir())
        .capabilities(["version"])
        .handler(CoreHandler::new(factory.clone()))
        .run()
        .await
        .unwrap();
    report.assert_success();
    assert_eq!(report.passed(), 1);
    assert!(format!("{:?}", CoreHandler::new(factory.clone())).contains("CoreHandler"));

    // the serverVersion step is skipped alone when `version` is not declared
    let report = ConformanceRunner::new(dir())
        .handler(CoreHandler::new(factory.clone()))
        .run()
        .await
        .unwrap();
    report.assert_success();
    assert_eq!(report.results[0].skipped_steps.len(), 1);

    // unknown ops and factory errors
    let handler = CoreHandler::new(factory);
    let s = scenario(
        json!({"id": "core.x", "category": "core", "steps": [{"op": "bogus", "expect": {"error": "UNSUPPORTED"}}]}),
    );
    let report = ConformanceRunner::new("/unused")
        .handler(handler)
        .run_scenarios(std::slice::from_ref(&s))
        .await;
    report.assert_success();
    let broken = CoreHandler::new(|| Err(Error::config("bad config")));
    let report = ConformanceRunner::new("/unused")
        .handler(broken)
        .run_scenarios(&[s])
        .await;
    assert_eq!(report.failed(), 1);
    assert!(ConformanceRunner::new("/nonexistent").run().await.is_err());
}
