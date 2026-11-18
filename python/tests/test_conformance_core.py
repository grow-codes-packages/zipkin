"""Conformance runner: substitution, expectations, skips, reports and the core scenario."""

from __future__ import annotations

import dataclasses
import datetime as dt
import enum
import json
from pathlib import Path
from typing import Any

import pytest

import grow_zipkin as sdk
from _support import MockServer, Reply, make_config
from grow_zipkin.testing import runner as r


def ctx(**variables: Any) -> r.StepContext:
    return r.StepContext({}, None, dict(variables))


# --------------------------------------------------------------------------- substitution


def test_substitution_values_keys_and_types() -> None:
    c = ctx(run="abc123", prefix="p/abc123/", traceId="t" * 32, n=5)
    assert c.substitute("x-${run}") == "x-abc123"
    assert c.substitute("${n}") == 5  # whole-string reference keeps the type
    assert c.substitute("${n}-${unknown}") == "5-${unknown}"
    assert c.substitute(["${prefix}a", 1, None]) == ["p/abc123/a", 1, None]
    expect = {"tree": {"conformance-root-${run}": ["child-a"], "child-b": ["${run}"]}}
    assert c.substitute(expect) == {
        "tree": {"conformance-root-abc123": ["child-a"], "child-b": ["abc123"]}
    }
    # keys always become strings, even for a typed variable
    assert c.substitute({"${n}": "${traceId}"}) == {"5": "t" * 32}
    assert c.substitute({1: "x"}) == {1: "x"}


# --------------------------------------------------------------------------- expectations


@dataclasses.dataclass
class Meta:
    name: str
    created_at: dt.datetime | None = None


class Color(enum.Enum):
    RED = "red"


@dataclasses.dataclass
class Span:
    name: str
    span_id: str
    parent_span_id: str | None = None


@dataclasses.dataclass
class Trace:
    trace_id: str
    spans: list[Span]


class SecretValue:
    """Stand-in with the name of the secrets category type (core-only builds have none)."""

    def __init__(self, text: str | None) -> None:
        self.text = text

    def reveal(self) -> str:
        if self.text is None:
            raise sdk.ValidationError("binary")
        return self.text

    def __repr__(self) -> str:
        return "SecretValue([REDACTED])"


def check(name: str, result: Any, expected: Any) -> str | None:
    return r._EXPECTATIONS[name](result, expected, ctx())


def test_expectations_pass_and_fail() -> None:
    secret = SecretValue("s3cr3t")
    assert check("reveal", secret, "s3cr3t") is None
    assert check("reveal", secret, "other") is not None
    assert check("reveal", None, "x") is not None
    assert check("equals", sdk.HealthStatus(True, "ok", {"a": "b"}), {"healthy": True}) is None
    assert check("equals", {"a": {"b": 1, "c": 2}}, {"a": {"b": 1}}) is None
    assert check("equals", [1, 2], [1, 2]) is None
    assert check("equals", [1, 2], [1]) is not None
    assert check("equals", {"a": 1}, {"a": 2}) is not None
    assert check("equals", "x", {"a": 1}) is not None
    assert check("equals", 1, True) is not None  # booleans are not numbers
    assert check("equals", secret, "s3cr3t") is None
    assert check("equals", SecretValue(None), "<binary>") is None
    assert check("equals", Color.RED, "red") is None
    assert check("null", None, True) is None
    assert check("null", 1, True) is not None
    assert check("notNull", 1, True) is None
    assert check("notNull", None, True) is not None
    assert check("true", True, True) is None
    assert check("true", 1, True) is not None
    assert check("false", False, True) is None
    assert check("false", None, True) is not None
    assert check("contains", ["svc-a", "svc-b"], "svc-b") is None
    assert check("contains", "hello world", "world") is None
    assert check("contains", {"k": 1}, "k") is None
    assert check("contains", 5, "x") is not None
    assert check("notContains", ["a"], "b") is None
    assert check("notContains", ["a"], "a") is not None
    assert check("count", {"a": 1, "b": 2}, 2) is None
    assert check("count", Trace("t", [Span("a", "1")]), 1) is None
    assert check("count", {"spans": [1, 2, 3]}, 3) is None
    assert check("count", 5, 1) is not None
    assert check("minCount", [1, 2, 3], 2) is None
    assert check("minCount", [1], 2) is not None
    assert check("minCount", None, 0) is not None
    assert check("sorted", [Meta("a"), Meta("b", dt.datetime(2026, 1, 1))], True) is None
    assert check("sorted", ["b", "a"], True) is not None
    assert check("sorted", "ab", True) is not None
    assert check("type", "s", "string") is None
    assert check("type", 1.5, "number") is None
    assert check("type", True, "number") is not None
    assert check("type", True, "boolean") is None
    assert check("type", Meta("x"), "object") is None
    assert check("type", (1,), "array") is None
    assert check("type", 1, "nonsense") is not None
    assert check("stringForm", secret, {"notContains": "s3cr3t"}) is None
    assert check("stringForm", "plain s3cr3t", {"notContains": "s3cr3t"}) is not None
    assert check("stringForm", {"k": "v"}, {"contains": "v"}) is None
    assert check("stringForm", {"k": "v"}, {"contains": "zzz"}) is not None
    assert check("stringForm", {1, 2}, {"contains": "1"}) is None  # not JSON serialisable


def test_tree_expectation() -> None:
    trace = Trace(
        "t",
        [
            Span("root", "1"),
            Span("child-a", "2", "1"),
            Span("child-b", "3", "1"),
            Span("grandchild", "4", "3"),
            Span("orphan", "5", "missing"),
        ],
    )
    good = {"root": ["child-b", "child-a"], "child-b": ["grandchild"]}
    assert check("tree", trace, good) is None
    assert check("tree", trace, {"root": ["child-a"]}) is not None
    assert check("tree", 5, good) == "result has no spans"


def test_to_jsonable() -> None:
    when = dt.datetime(2026, 1, 2, tzinfo=dt.timezone.utc)
    assert r.to_jsonable(Meta("n", when)) == {"name": "n", "createdAt": when.isoformat()}
    assert r.to_jsonable({"a": (1, {2})}) == {"a": [1, [2]]}
    assert r.to_jsonable(dt.date(2026, 1, 2)) == "2026-01-02"


# --------------------------------------------------------------------------- runner


def scenario(category: str = "fake", **kw: Any) -> dict[str, Any]:
    doc: dict[str, Any] = {"id": f"{category}.test", "category": category, "steps": []}
    doc.update(kw)
    return doc


class FakeTarget:
    def __init__(self) -> None:
        self.closed = 0
        self.values: dict[str, Any] = {}

    def close(self) -> None:
        self.closed += 1


def _fake_ops() -> None:
    def put(c: r.StepContext, args: dict[str, Any]) -> Any:
        c.target.values[args["key"]] = args["value"]
        return args["value"]

    def read(c: r.StepContext, args: dict[str, Any]) -> Any:
        return c.target.values.get(args["key"])

    def fail(c: r.StepContext, args: dict[str, Any]) -> Any:
        if args.get("plain"):
            raise RuntimeError("plain failure")
        raise sdk.error_for_code(args.get("code", "NOT_FOUND"))("op: failed")

    async def aread(c: r.StepContext, args: dict[str, Any]) -> Any:
        return c.target.values.get(args["key"])

    r.register_ops(
        "fake",
        {"put": put, "read": read, "fail": fail, "aread": aread},
        teardown=lambda t: t.close(),
    )


_fake_ops()


def test_runner_success_save_and_teardown() -> None:
    target = FakeTarget()
    runner = r.ConformanceRunner({"fake": lambda c: target}, random_id=lambda: "run00001")
    doc = scenario(
        steps=[
            {"op": "put", "args": {"key": "a", "value": "v-${run}"}, "save": "saved"},
            {"op": "read", "args": {"key": "a"}, "expect": {"equals": "${saved}"}},
            {"op": "aread", "args": {"key": "a"}, "expect": {"equals": "v-run00001"}},
            {"op": "fail", "expect": {"error": "NOT_FOUND"}},
            {"op": "read", "requires": ["exotic"], "args": {"key": "a"}},
            {
                "op": "put",
                "args": {"key": "p", "value": "${prefix}"},
                "expect": {"equals": "grow-conformance/run00001/"},
            },
        ]
    )
    result = runner.run_scenario(doc)
    assert result.status == "passed", result.reason
    assert [s.status for s in result.steps] == ["passed"] * 4 + ["skipped", "passed"]
    assert target.closed == 1
    assert result.duration_s >= 0


@pytest.mark.parametrize(
    ("steps", "fragment"),
    [
        ([{"op": "nope"}], "unknown op"),
        ([{"op": "read", "args": {"key": "a"}, "expect": {"weird": 1}}], "unknown expectation"),
        ([{"op": "read", "args": {"key": "a"}, "expect": {"notNull": True}}], "notNull"),
        ([{"op": "fail"}], "raised NOT_FOUND"),
        (
            [{"op": "fail", "args": {"code": "CONFLICT"}, "expect": {"error": "NOT_FOUND"}}],
            "got CONFLICT",
        ),
        ([{"op": "read", "args": {"key": "a"}, "expect": {"error": "NOT_FOUND"}}], "none raised"),
        ([{"op": "fail", "args": {"plain": True}}], "RuntimeError"),
    ],
)
def test_runner_step_failures(steps: list[dict[str, Any]], fragment: str) -> None:
    target = FakeTarget()
    runner = r.ConformanceRunner({"fake": lambda c: target})
    result = runner.run_scenario(scenario(steps=[*steps, {"op": "read", "args": {"key": "z"}}]))
    assert result.status == "failed"
    assert fragment in result.reason
    assert len(result.steps) == len(steps)  # stops at the first failure
    assert target.closed == 1


def test_runner_skips_setup_and_teardown_failures() -> None:
    runner = r.ConformanceRunner({"fake": lambda c: FakeTarget()}, capabilities={"write"})
    skipped = runner.run_scenario(scenario(requires=["write", "query"]))
    assert skipped.status == "skipped" and "query" in skipped.reason
    no_factory = runner.run_scenario(scenario("other"))
    assert no_factory.status == "skipped" and "no factory" in no_factory.reason

    def broken_factory(c: r.ScenarioContext) -> Any:
        raise RuntimeError("cannot connect")

    setup = r.ConformanceRunner({"fake": broken_factory}).run_scenario(scenario())
    assert setup.status == "failed" and "setup failed" in setup.reason

    class BadClose(FakeTarget):
        def close(self) -> None:
            raise RuntimeError("close failed")

    teardown = r.ConformanceRunner({"fake": lambda c: BadClose()}).run_scenario(scenario())
    assert teardown.status == "failed" and "teardown failed" in teardown.reason
    both = r.ConformanceRunner({"fake": lambda c: BadClose()}).run_scenario(
        scenario(steps=[{"op": "nope"}])
    )
    assert "unknown op" in both.reason


def test_factory_context_and_async_factory() -> None:
    seen: list[r.ScenarioContext] = []

    async def factory(c: r.ScenarioContext) -> FakeTarget:
        seen.append(c)
        return FakeTarget()

    runner = r.ConformanceRunner(
        {"fake": factory}, prefix="custom/${run}/", service_name="svc", random_id=lambda: "r1"
    )
    doc = scenario(
        batchOptions={"maxBatchSize": 5},
        steps=[
            {
                "op": "put",
                "args": {"key": "s", "value": "${serviceName}"},
                "expect": {"equals": "svc"},
            }
        ],
    )
    assert runner.run_scenario(doc).status == "passed"
    assert seen[0].prefix == "custom/r1/" and seen[0].run == "r1"
    assert seen[0].service_name == "svc" and seen[0].scenario["batchOptions"] == {"maxBatchSize": 5}


def test_report() -> None:
    report = r.ConformanceReport(
        [
            r.ScenarioResult("a", "core", "passed"),
            r.ScenarioResult("b", "core", "skipped", "missing capabilities: write"),
            r.ScenarioResult("c", "core", "failed", "step 0 (x): boom"),
        ]
    )
    assert [x.id for x in report.passed] == ["a"]
    assert not report.ok
    data = report.to_dict()
    assert (data["passed"], data["failed"], data["skipped"]) == (1, 1, 1)
    json.dumps(data)
    assert "FAILED  c — step 0 (x): boom" in report.summary()
    with pytest.raises(AssertionError):
        report.raise_on_failure()
    r.ConformanceReport([r.ScenarioResult("a", "core", "passed")]).raise_on_failure()
    assert "core" in r.registered_categories()


def test_scenario_loading(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    real = r.find_scenarios_dir()
    assert (real / "core.health.json").is_file()
    ids = [s["id"] for s in r.load_scenarios(real, ["core"])]
    assert ids == ["core.health"]
    assert len(r.load_scenarios(real)) >= len(ids)
    monkeypatch.setenv(r.SCENARIOS_ENV, str(tmp_path))
    assert r.find_scenarios_dir() == tmp_path
    monkeypatch.delenv(r.SCENARIOS_ENV)
    nested = tmp_path / "a" / "conformance" / "scenarios"
    nested.mkdir(parents=True)
    (nested / "x.json").write_text(json.dumps(scenario("fake")))
    assert r.find_scenarios_dir(tmp_path / "a") == nested
    runner = r.ConformanceRunner({"fake": lambda c: FakeTarget()}, scenarios_dir=nested)
    assert runner.run().ok
    monkeypatch.setattr(r, "__file__", str(tmp_path / "nowhere.py"))
    monkeypatch.chdir(tmp_path)
    with pytest.raises(FileNotFoundError):
        r.find_scenarios_dir(Path("/"))


def test_context_resolve_and_close() -> None:
    c = ctx()
    assert c.resolve(5) == 5

    async def seven() -> int:
        return 7

    assert c.resolve(seven()) == 7
    c.close()
    c.close()


# --------------------------------------------------------------------------- core scenario


def demo_server(server: MockServer) -> None:
    server.defaults["/health"] = Reply(200, {"status": "ok"})
    server.defaults["/v1/ping"] = Reply(200, {"pong": True})
    server.defaults["/version"] = Reply(200, {"version": "1.0.0"})


@pytest.mark.parametrize("scenario_doc", r.pytest_params(categories=["core"]))
def test_core_scenarios_sync(server: MockServer, scenario_doc: dict[str, Any]) -> None:
    demo_server(server)
    runner = r.ConformanceRunner(
        {"core": lambda c: sdk.ZipkinClient(make_config(server.url, token="t-1234"))},
        capabilities={"version"},
    )
    result = r.assert_scenario(runner, scenario_doc)
    assert result.status == "passed"


def test_core_scenario_async_client(server: MockServer) -> None:
    demo_server(server)
    runner = r.ConformanceRunner(
        {"core": lambda c: sdk.AsyncZipkinClient(make_config(server.url))},
    )
    report = runner.run()
    assert report.ok, report.summary()
    step_status = [s.status for s in report.results[0].steps]
    assert step_status == ["passed", "passed", "skipped", "passed", "passed"]


def test_assert_scenario_skip_and_fail(server: MockServer) -> None:
    runner = r.ConformanceRunner({"fake": lambda c: FakeTarget()})
    with pytest.raises(pytest.skip.Exception):
        r.assert_scenario(runner, scenario(requires=["x"]))
    with pytest.raises(pytest.fail.Exception):
        r.assert_scenario(runner, scenario(steps=[{"op": "nope"}]))
