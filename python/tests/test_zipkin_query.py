"""ZipkinQueryClient / AsyncZipkinQueryClient against a mock server fed with recorded fixtures."""

from __future__ import annotations

import datetime as dt
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest

import grow_zipkin as sdk
from _fixtures import fixture_reply, response_body
from _support import MockServer, Reply, make_config

TRACE = "5af7183fb1d4cf5fad1c0df61d0a9b8c"
NOW = 1_795_035_940.0  # epoch seconds used as the fake clock


def query(url: str, **kw: Any) -> sdk.ZipkinQueryClient:
    return sdk.ZipkinQueryClient(make_config(url, **kw), clock=lambda: NOW)


def aquery(url: str, **kw: Any) -> sdk.AsyncZipkinQueryClient:
    return sdk.AsyncZipkinQueryClient(make_config(url, **kw), clock=lambda: NOW)


def params(server: MockServer, index: int = -1) -> dict[str, list[str]]:
    return parse_qs(urlsplit(server.seen[index].path).query)


def span(trace_id: str, span_id: str = "b" * 16, **extra: Any) -> dict[str, Any]:
    return {"traceId": trace_id, "id": span_id, **extra}


# --------------------------------------------------------------------------- traces


def test_get_trace_spans_and_trace(server: MockServer) -> None:
    server.enqueue(f"/api/v2/trace/{TRACE}", fixture_reply("getTrace.ok"))
    server.enqueue(f"/api/v2/trace/{TRACE}", fixture_reply("getTrace.ok"))
    server.enqueue("/api/v2/trace/" + "f" * 32, fixture_reply("getTrace.notFound"))
    server.enqueue("/api/v2/trace/" + "e" * 32, Reply(200, []))
    with query(server.url) as q:
        spans = q.get_trace_spans(TRACE.upper())
        assert spans is not None and spans[0].kind == "SERVER"
        trace = q.get_trace(TRACE)
        assert trace is not None and trace.trace_id == TRACE
        assert q.get_trace("f" * 32) is None
        assert q.get_trace_spans("e" * 32) is None
        with pytest.raises(sdk.ValidationError):
            q.get_trace("not-a-trace")
    assert server.seen[0].path == f"/api/v2/trace/{TRACE}"


def test_get_trace_maps_text_errors(server: MockServer) -> None:
    server.enqueue("/api/v2/trace/" + "a" * 16, fixture_reply("getTrace.invalid400"))
    with pytest.raises(sdk.ValidationError, match="lower-hex"):
        query(server.url).get_trace_spans("a" * 16)
    server.enqueue("/api/v2/trace/" + "a" * 16, Reply(503, "storage down", content_type="text/*"))
    with pytest.raises(sdk.UnavailableError, match="storage down"):
        query(server.url, retry=sdk.RetryPolicy(max_retries=0)).get_trace("a" * 16)


def test_get_traces_all_filters(server: MockServer) -> None:
    server.enqueue("/api/v2/traces", fixture_reply("getTraces.allFilters"))
    traces = query(server.url).get_traces(
        sdk.ZipkinTraceQuery(service_name="grow-frontend", limit=1),
        span_name="get /api/orders",
        remote_service_name="  ",
        annotation_query=" http.method=GET ",
        tags={"grow.test": "run-a"},
        annotations=["cache.miss"],
        min_duration_micros=100_000,
        max_duration_micros=200_000,
        end_ts=dt.datetime.fromtimestamp(NOW, dt.timezone.utc),
        lookback_s=dt.timedelta(hours=1),
        limit=5,
    )
    assert [t[0].trace_id for t in traces] == [TRACE]
    assert params(server) == {
        "serviceName": ["grow-frontend"],
        "spanName": ["get /api/orders"],
        "annotationQuery": ["http.method=GET and grow.test=run-a and cache.miss"],
        "minDuration": ["100000"],
        "maxDuration": ["200000"],
        "endTs": ["1795035940000"],
        "lookback": ["3600000"],
        "limit": ["5"],
    }
    server.enqueue("/api/v2/traces", Reply(200, []))
    assert query(server.url).get_traces() == []
    assert params(server) == {}
    server.enqueue("/api/v2/traces", Reply(200, []))
    query(server.url).get_traces(end_ts=dt.datetime(2026, 1, 1))  # naive = UTC
    assert params(server)["endTs"] == ["1767225600000"]


def test_get_traces_client_side_terms(server: MockServer) -> None:
    hit = [span("a" * 16, tags={"note": "salt and pepper"})]
    miss = [span("c" * 16, tags={"note": "salt"})]
    annotated = [span("d" * 16, annotations=[{"timestamp": 1, "value": "x and y"}])]
    server.defaults["/api/v2/traces"] = Reply(200, [hit, miss, annotated])
    q = query(server.url)
    found = q.get_traces(service_name="s", tags={"note": "salt and pepper"})
    assert [t[0].trace_id for t in found] == ["a" * 16]
    assert "annotationQuery" not in params(server)
    assert [t[0].trace_id for t in q.get_traces(annotations=["x and y"])] == ["d" * 16]
    assert [t[0].trace_id for t in q.get_traces(annotations=["a=b"])] == []
    assert [t[0].trace_id for t in q.get_traces(annotations=["note and x"])] == []


@pytest.mark.parametrize(
    ("filters", "fragment"),
    [
        ({"max_duration_micros": 5}, "needs min_duration_micros"),
        ({"min_duration_micros": 10, "max_duration_micros": 5}, "must not be below"),
        ({"min_duration_micros": -1}, "non-negative"),
        ({"min_duration_micros": True}, "non-negative"),
        ({"limit": 0}, "limit"),
        ({"limit": True}, "limit"),
        ({"lookback_s": 0}, "lookback_s"),
        ({"lookback_s": "x"}, "lookback_s"),
        ({"end_ts": 0}, "end_ts"),
        ({"end_ts": "now"}, "end_ts"),
        ({"tags": {"": "x"}}, "must not be empty"),
    ],
)
def test_get_traces_validation(filters: dict[str, Any], fragment: str) -> None:
    q = query("http://127.0.0.1:9")
    with pytest.raises(sdk.ValidationError, match=fragment):
        q.get_traces(**filters)


def test_build_annotation_query() -> None:
    assert sdk.build_annotation_query() is None
    assert sdk.build_annotation_query({"a": "1"}, ["b"]) == "a=1 and b"
    for tags, terms in (({"": "x"}, []), ({"a": "x and y"}, []), ({}, ["k=v"])):
        with pytest.raises(sdk.ValidationError):
            sdk.build_annotation_query(tags, terms)


def test_get_trace_many(server: MockServer) -> None:
    server.enqueue("/api/v2/traceMany", fixture_reply("getTraceMany.ok"))
    q = query(server.url)
    body = response_body("getTraceMany.ok")
    ids = [t[0]["traceId"] for t in body]
    many = q.get_trace_many([ids[-1], ids[0].upper(), "f" * 32, ids[-1].rjust(32, "0")])
    assert [t[0].trace_id for t in many] == [ids[-1], ids[0]]
    assert params(server)["traceIds"] == [f"{ids[-1]},{ids[0]},{'f' * 32}"]
    assert q.get_trace_many([]) == []
    with pytest.raises(sdk.ValidationError):
        q.get_trace_many("a" * 16)
    server.enqueue("/api/v2/trace/" + "a" * 16, Reply(200, [span("a" * 16)]))
    assert len(q.get_trace_many(["a" * 16, "0" * 16 + "a" * 16])) == 1
    server.enqueue("/api/v2/trace/" + "a" * 16, Reply(404, "nope", content_type="text/*"))
    assert q.get_trace_many(["a" * 16]) == []


def test_get_trace_many_chunks(server: MockServer) -> None:
    ids = [f"{i + 1:032x}" for i in range(201)]
    server.enqueue("/api/v2/traceMany", Reply(200, [[span(ids[150])], []]))
    server.enqueue("/api/v2/traceMany", Reply(200, [[span(ids[0])]]))
    server.enqueue(f"/api/v2/trace/{ids[200]}", Reply(200, [span(ids[200])]))
    many = query(server.url).get_trace_many(ids)
    assert [t[0].trace_id for t in many] == [ids[0], ids[150], ids[200]]
    chunks = [len(params(server, i)["traceIds"][0].split(",")) for i in (0, 1)]
    assert chunks == [100, 100]
    assert server.seen[2].path == f"/api/v2/trace/{ids[200]}"


def test_search_traces(server: MockServer) -> None:
    server.defaults["/api/v2/traces"] = fixture_reply("getTraces.allFilters")
    q = query(server.url)
    summaries = q.search_traces(
        sdk.TraceSearchQuery(
            service="grow-frontend",
            operation="get /api/orders",
            tags={"http.method": "GET"},
            min_duration_micros=1,
            max_duration_micros=10_000_000,
            start=dt.datetime.fromtimestamp(NOW - 120, dt.timezone.utc),
            end=dt.datetime.fromtimestamp(NOW, dt.timezone.utc),
            limit=3,
        )
    )
    assert summaries[0].trace_id == TRACE
    assert summaries[0].root_name == "get /api/orders"
    assert params(server)["lookback"] == ["120000"]
    assert params(server)["limit"] == ["3"]
    q.search_traces()
    assert params(server) == {"endTs": ["1795035940000"], "lookback": ["3600000"], "limit": ["20"]}
    with pytest.raises(sdk.ValidationError, match="start must not be after end"):
        q.search_traces(
            sdk.TraceSearchQuery(
                start=dt.datetime.fromtimestamp(NOW + 10, dt.timezone.utc),
                end=dt.datetime.fromtimestamp(NOW, dt.timezone.utc),
            )
        )


def test_wait_for_trace(server: MockServer) -> None:
    server.enqueue("/api/v2/trace/" + "a" * 32, Reply(404, "x", content_type="text/*"))
    server.enqueue("/api/v2/trace/" + "a" * 32, Reply(200, [span("a" * 32)]))
    trace = query(server.url).wait_for_trace("a" * 32, interval_s=0.01, timeout_s=5)
    assert len(trace.spans) == 1


# --------------------------------------------------------------------------- names


def test_names_and_autocomplete(server: MockServer) -> None:
    server.enqueue("/api/v2/services", fixture_reply("listServices.ok"))
    server.enqueue("/api/v2/spans", fixture_reply("listSpans.ok"), fixture_reply("listSpans.ok"))
    server.enqueue("/api/v2/remoteServices", fixture_reply("listRemoteServices.ok"))
    server.enqueue("/api/v2/autocompleteKeys", fixture_reply("autocompleteKeys.ok"))
    server.enqueue("/api/v2/autocompleteValues", fixture_reply("autocompleteValues.ok"))
    q = query(server.url)
    services = q.list_services()
    assert services == sorted(response_body("listServices.ok"))
    assert q.list_spans("Grow-Frontend") == ["get /api/orders", "get /orders"]
    assert params(server) == {"serviceName": ["Grow-Frontend"]}
    assert q.list_operations("grow-frontend") == ["get /api/orders", "get /orders"]
    assert q.list_remote_services("grow-frontend") == ["grow-backend"]
    assert q.autocomplete_keys() == response_body("autocompleteKeys.ok")
    assert q.autocomplete_values("grow.test") == sorted(response_body("autocompleteValues.ok"))
    assert params(server) == {"key": ["grow.test"]}
    for call in (q.list_spans, q.list_remote_services, q.autocomplete_values):
        with pytest.raises(sdk.ValidationError, match="required"):
            call(" ")


def test_missing_param_error_text(server: MockServer) -> None:
    server.enqueue("/api/v2/spans", fixture_reply("listSpans.missing400"))
    with pytest.raises(sdk.ValidationError, match="Mandatory parameter"):
        query(server.url).list_spans("x")


def test_get_dependencies(server: MockServer) -> None:
    server.defaults["/api/v2/dependencies"] = fixture_reply("getDependencies.ok")
    q = query(server.url)
    links = q.get_dependencies(end_ts=1_795_035_940_000, lookback_s=3600)
    assert links[0] == sdk.ZipkinDependencyLink("grow-frontend", "grow-backend", 1, 1)
    assert params(server) == {"endTs": ["1795035940000"], "lookback": ["3600000"]}
    q.get_dependencies()
    assert params(server) == {"endTs": ["1795035940000"]}
    q.get_dependencies(
        start=dt.datetime.fromtimestamp(NOW - 60, dt.timezone.utc),
        end=dt.datetime.fromtimestamp(NOW, dt.timezone.utc),
    )
    assert params(server) == {"endTs": ["1795035940000"], "lookback": ["60000"]}
    with pytest.raises(sdk.ValidationError, match="lookback"):
        q.get_dependencies(start=dt.datetime.fromtimestamp(NOW + 5, dt.timezone.utc))
    with pytest.raises(sdk.ValidationError, match="lookback"):
        q.get_dependencies(lookback_s=-1)


def test_owned_and_shared_lifecycle(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.defaults["/api/v2/services"] = Reply(200, ["a"])
    q = sdk.ZipkinQueryClient.from_env({sdk.ENV_PREFIX + "URL": server.url})
    assert q.list_services() == ["a"]
    q.close()
    q.close()
    with pytest.raises(sdk.CancelledError):
        q.list_services()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    default = sdk.ZipkinQueryClient()
    assert default.config.url == server.url
    default.close()
    shared_client = sdk.ZipkinClient(make_config(server.url))
    shared = sdk.ZipkinQueryClient(shared_client)
    shared.close()  # no-op: the client owns the connections
    assert shared.list_services() == ["a"]
    shared_client.close()


def test_cancel_token(server: MockServer) -> None:
    token = sdk.CancelToken()
    token.cancel()
    with pytest.raises(sdk.CancelledError):
        query(server.url).list_services(cancel=token)


# --------------------------------------------------------------------------- async


async def test_async_query_client(server: MockServer) -> None:
    server.defaults[f"/api/v2/trace/{TRACE}"] = fixture_reply("getTrace.ok")
    server.enqueue("/api/v2/trace/" + "f" * 32, fixture_reply("getTrace.notFound"))
    server.defaults["/api/v2/traces"] = fixture_reply("getTraces.allFilters")
    server.defaults["/api/v2/traceMany"] = Reply(200, [[span("a" * 16)], [span(TRACE)]])
    server.defaults["/api/v2/services"] = fixture_reply("listServices.ok")
    server.defaults["/api/v2/spans"] = fixture_reply("listSpans.ok")
    server.defaults["/api/v2/remoteServices"] = fixture_reply("listRemoteServices.ok")
    server.defaults["/api/v2/autocompleteKeys"] = fixture_reply("autocompleteKeys.ok")
    server.defaults["/api/v2/autocompleteValues"] = fixture_reply("autocompleteValues.ok")
    server.defaults["/api/v2/dependencies"] = fixture_reply("getDependencies.ok")
    async with aquery(server.url) as q:
        assert (await q.get_trace(TRACE)) is not None
        assert (await q.get_trace("f" * 32)) is None
        assert len(await q.get_traces(service_name="grow-frontend")) == 1
        many = await q.get_trace_many([TRACE, "a" * 16, "c" * 16])
        assert [t[0].trace_id for t in many] == [TRACE, "a" * 16]
        assert await q.get_trace_many([]) == []
        assert len(await q.get_trace_many([TRACE])) == 1
        ids = [f"{i + 1:032x}" for i in range(101)]
        server.enqueue(f"/api/v2/trace/{ids[100]}", Reply(404, "x", content_type="text/*"))
        assert len(await q.get_trace_many(ids)) == 0
        summaries = await q.search_traces(sdk.TraceSearchQuery(service="grow-frontend"))
        assert summaries[0].trace_id == TRACE
        assert "grow-frontend" in await q.list_services()
        assert await q.list_operations("grow-frontend") == ["get /api/orders", "get /orders"]
        assert await q.list_remote_services("grow-frontend") == ["grow-backend"]
        assert await q.autocomplete_keys() == response_body("autocompleteKeys.ok")
        assert "run-a" in await q.autocomplete_values("grow.test")
        links = await q.get_dependencies(lookback_s=60)
        assert links[1].error_count == 0
        trace = await q.wait_for_trace(TRACE, interval_s=0.01)
        assert trace.trace_id == TRACE
    with pytest.raises(sdk.CancelledError):
        await q.list_services()
    standalone = sdk.AsyncZipkinQueryClient.from_env({sdk.ENV_PREFIX + "URL": server.url})
    await standalone.close()
    shared_client = sdk.AsyncZipkinClient(make_config(server.url))
    shared = sdk.AsyncZipkinQueryClient(shared_client)
    await shared.aclose()
    assert await shared.list_spans("x") == ["get /api/orders", "get /orders"]
    await shared_client.aclose()


async def test_async_default_config(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    q = sdk.AsyncZipkinQueryClient()
    assert q.config.url == server.url
    await q.aclose()
