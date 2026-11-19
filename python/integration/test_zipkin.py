"""ZipkinReporter and ZipkinQueryClient (sync and async) against the real Zipkin 3.6.1.

Every write goes through a reporter and is read back through the query API.
"""

from __future__ import annotations

import asyncio
import os
import secrets
import time
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import pytest

import grow_zipkin as sdk

RUN = "".join(c for c in os.environ.get("GROW_TEST_RUN_ID", "local").lower() if c.isalnum())[:16]
TAG = f"{RUN}-{secrets.token_hex(3)}"
FRONTEND = f"sdk-py-fe-{TAG}"
BACKEND = f"sdk-py-be-{TAG}"
LONELY = f"sdk-py-gz-{TAG}"
SALTY = f"sdk-py-salt-{TAG}"
ASYNC_SVC = f"sdk-py-async-{TAG}"


def hex_id(n: int) -> str:
    return secrets.token_hex(n)


TRACE = hex_id(16)
TRACE64 = hex_id(8)
SALT_TRACE = hex_id(16)
ROOT, CLIENT_ID, SERVER_ID, LONELY_ID, SALT_ID = (hex_id(8) for _ in range(5))
NOW_US = int(time.time() * 1_000_000)


def now_ms() -> int:
    return int(time.time() * 1000)


@pytest.fixture(scope="module")
def zipkin(env: dict[str, str]) -> Iterator[sdk.ZipkinClient]:
    client = sdk.ZipkinClient.from_env(env)
    # frontend SERVER root → frontend CLIENT → backend SERVER (error), JSON without compression.
    client.reporter.report(
        [
            {
                "traceId": TRACE,
                "id": ROOT,
                "kind": "SERVER",
                "name": "GET /API/Orders",
                "timestamp": NOW_US,
                "duration": 150_000,
                "localEndpoint": {"serviceName": FRONTEND, "ipv4": "10.0.0.1", "port": 8080},
                "annotations": [{"timestamp": NOW_US + 1000, "value": "cache.miss"}],
                "tags": {"grow.test": TAG, "http.method": "GET"},
            },
            sdk.ZipkinSpan(
                trace_id=TRACE,
                parent_id=ROOT,
                id=CLIENT_ID,
                kind="client",
                name="get /orders",
                timestamp=NOW_US + 5000,
                duration=100_000,
                local_endpoint=sdk.ZipkinEndpoint(FRONTEND),
                remote_endpoint=sdk.ZipkinEndpoint(BACKEND, port=9000),
                tags={"grow.test": TAG, "http.method": "GET"},
            ),
            {
                "traceId": TRACE,
                "parentId": CLIENT_ID,
                "id": SERVER_ID,
                "kind": "SERVER",
                "name": "get /orders",
                "timestamp": NOW_US + 10_000,
                "duration": 80_000,
                "localEndpoint": {"serviceName": BACKEND, "port": 9000},
                "tags": {"error": "boom", "grow.test": TAG},
            },
        ]
    )
    # A 64-bit trace with upper-case ids and non-string tags, gzip-compressed.
    client.create_reporter(compression="gzip").report(
        [
            {
                "traceId": TRACE64.upper(),
                "id": LONELY_ID.upper(),
                "name": "lonely-op",
                "timestamp": NOW_US,
                "duration": 5000,
                "localEndpoint": {"serviceName": LONELY},
                "tags": {"grow.test": TAG, "flag": True, "n": 7},
            }
        ]
    )
    # A tag value Zipkin's annotationQuery cannot express.
    sdk.ZipkinReporter(client).report(
        [
            {
                "traceId": SALT_TRACE,
                "id": SALT_ID,
                "name": "season",
                "timestamp": NOW_US,
                "duration": 1000,
                "localEndpoint": {"serviceName": SALTY},
                "tags": {"note": "salt and pepper"},
            }
        ]
    )
    yield client
    client.close()


def names(spans: list[sdk.ZipkinSpan]) -> list[str]:
    return sorted(s.name or "" for s in spans)


# --------------------------------------------------------------------------- round trip


def test_get_trace_spans_returns_the_reported_spans(zipkin: sdk.ZipkinClient) -> None:
    spans = zipkin.query.get_trace_spans(TRACE)
    assert spans is not None
    assert names(spans) == ["get /api/orders", "get /orders", "get /orders"]
    root = next(s for s in spans if s.id == ROOT)
    assert root.kind == "SERVER"
    assert root.annotations == (sdk.ZipkinAnnotation(NOW_US + 1000, "cache.miss"),)
    client_span = next(s for s in spans if s.id == CLIENT_ID)
    assert client_span.remote_endpoint is not None
    assert client_span.remote_endpoint.service_name == BACKEND


def test_gzip_upper_case_ids_and_non_string_tags(zipkin: sdk.ZipkinClient) -> None:
    spans = zipkin.query.get_trace_spans(TRACE64)
    assert spans is not None
    assert len(spans) == 1
    assert (spans[0].trace_id, spans[0].id) == (TRACE64, LONELY_ID)
    assert spans[0].tags == {"flag": "true", "grow.test": TAG, "n": "7"}


def test_get_trace_normalized_missing_and_invalid(zipkin: sdk.ZipkinClient) -> None:
    trace = zipkin.query.get_trace(TRACE)
    assert trace is not None
    assert trace.trace_id == TRACE
    root = trace.spans[0]
    assert (root.span_id, root.kind, root.service_name) == (ROOT, "server", FRONTEND)
    assert [(e.name, e.time_micros, dict(e.attributes)) for e in root.events] == [
        ("cache.miss", NOW_US + 1000, {})
    ]
    failed = next(s for s in trace.spans if s.span_id == SERVER_ID)
    assert failed.status == sdk.SpanStatus("error", "boom")
    assert failed.parent_span_id == CLIENT_ID
    padded = zipkin.query.get_trace(TRACE64)
    assert padded is not None
    assert padded.trace_id == "0" * 16 + TRACE64
    assert zipkin.query.get_trace(hex_id(16)) is None
    with pytest.raises(sdk.ValidationError):
        zipkin.query.get_trace("not-a-trace")
    with pytest.raises(sdk.ValidationError):
        zipkin.reporter.report([{"traceId": "zz", "id": ROOT}])


def test_wait_for_trace(zipkin: sdk.ZipkinClient) -> None:
    trace = zipkin.query.wait_for_trace(TRACE, min_spans=3, interval_s=0.2)
    assert len(trace.spans) == 3


# --------------------------------------------------------------------------- search


def test_get_traces_with_every_filter(zipkin: sdk.ZipkinClient) -> None:
    q = zipkin.query
    traces = q.get_traces(
        service_name=FRONTEND.upper(),
        span_name="get /api/orders",
        tags={"grow.test": TAG},
        annotations=["cache.miss"],
        min_duration_micros=100_000,
        max_duration_micros=200_000,
        end_ts=now_ms() + 60_000,
        lookback_s=3600,
        limit=5,
    )
    assert [t[0].trace_id for t in traces] == [TRACE]
    raw = q.get_traces(
        sdk.ZipkinTraceQuery(
            service_name=FRONTEND,
            annotation_query=sdk.build_annotation_query({"http.method": "GET"}, ["cache.miss"]),
        )
    )
    assert len(raw) == 1
    remote = q.get_traces(service_name=FRONTEND, remote_service_name=BACKEND)
    assert remote[0][0].trace_id == TRACE
    assert q.get_traces(service_name=FRONTEND, min_duration_micros=10_000_000) == []


def test_tag_values_with_and_are_matched_client_side(zipkin: sdk.ZipkinClient) -> None:
    hit = zipkin.query.get_traces(service_name=SALTY, tags={"note": "salt and pepper"})
    assert [t[0].trace_id for t in hit] == [SALT_TRACE]
    assert zipkin.query.get_traces(service_name=SALTY, tags={"note": "salt and sugar"}) == []


def test_get_trace_many(zipkin: sdk.ZipkinClient) -> None:
    q = zipkin.query
    many = q.get_trace_many([TRACE64, TRACE.upper(), hex_id(16), "0" * 16 + TRACE64, TRACE])
    assert [t[0].trace_id for t in many] == [TRACE64, TRACE]
    # More than one chunk (100 ids per request) still finds both traces.
    chunked = q.get_trace_many([*(hex_id(16) for _ in range(150)), TRACE, TRACE64])
    assert [t[0].trace_id for t in chunked] == [TRACE, TRACE64]
    assert q.get_trace_many([]) == []
    assert len(q.get_trace_many([TRACE])[0]) == 3


def test_search_traces(zipkin: sdk.ZipkinClient) -> None:
    now = datetime.now(timezone.utc)
    summaries = zipkin.query.search_traces(
        sdk.TraceSearchQuery(
            service=FRONTEND,
            operation="get /api/orders",
            tags={"grow.test": TAG},
            start=now - timedelta(hours=1),
            end=now + timedelta(minutes=1),
            limit=10,
        )
    )
    assert summaries == [
        sdk.TraceSummary(
            trace_id=TRACE,
            start_micros=NOW_US,
            duration_micros=150_000,
            span_count=3,
            root_service_name=FRONTEND,
            root_name="get /api/orders",
        )
    ]
    # default window: the last hour
    assert [
        s.trace_id for s in zipkin.query.search_traces(sdk.TraceSearchQuery(service=FRONTEND))
    ] == [TRACE]


# --------------------------------------------------------------------------- names


def test_names(zipkin: sdk.ZipkinClient) -> None:
    q = zipkin.query
    services = q.list_services()
    for service in (FRONTEND, BACKEND, LONELY):
        assert service in services
    assert q.list_spans(FRONTEND) == ["get /api/orders", "get /orders"]
    assert q.list_operations(BACKEND) == ["get /orders"]
    assert q.list_remote_services(FRONTEND) == [BACKEND]
    assert q.list_spans(f"unknown-{TAG}") == []


def test_dependencies(zipkin: sdk.ZipkinClient) -> None:
    links = zipkin.query.get_dependencies(end_ts=now_ms() + 60_000, lookback_s=3600)
    assert sdk.ZipkinDependencyLink(FRONTEND, BACKEND, 1, 1) in links
    now = datetime.now(timezone.utc)
    window = zipkin.query.get_dependencies(
        start=now - timedelta(hours=1), end=now + timedelta(minutes=1)
    )
    assert any(link.parent == FRONTEND and link.child == BACKEND for link in window)


def test_autocomplete(zipkin: sdk.ZipkinClient, env: dict[str, str]) -> None:
    keys = zipkin.query.autocomplete_keys()
    for key in env.get("ZIPKIN_AUTOCOMPLETE_KEYS", "grow.test,http.method").split(","):
        assert key in keys
    assert TAG in zipkin.query.autocomplete_values("grow.test")
    assert "GET" in zipkin.query.autocomplete_values("http.method")
    assert zipkin.query.autocomplete_values(f"not-allowlisted-{TAG}") == []


def test_standalone_clients_own_their_connections(
    zipkin: sdk.ZipkinClient, env: dict[str, str]
) -> None:
    with sdk.ZipkinQueryClient.from_env(env) as standalone:
        assert FRONTEND in standalone.list_services()
    trace_id = hex_id(16)
    with sdk.ZipkinReporter.from_env(env, max_batch_size=1) as reporter:
        reporter.report(
            [
                {
                    "traceId": trace_id,
                    "id": hex_id(8),
                    "name": "a",
                    "timestamp": NOW_US,
                    "localEndpoint": {"serviceName": LONELY},
                },
                {
                    "traceId": trace_id,
                    "id": hex_id(8),
                    "name": "b",
                    "timestamp": NOW_US,
                    "localEndpoint": {"serviceName": LONELY},
                },
            ]
        )
    spans = zipkin.query.get_trace_spans(trace_id)
    assert spans is not None
    assert len(spans) == 2


# --------------------------------------------------------------------------- async


def test_async_reporter_and_query(env: dict[str, str]) -> None:
    async def run() -> None:
        trace_id, root_id, child_id = hex_id(16), hex_id(8), hex_id(8)
        async with sdk.AsyncZipkinClient.from_env(env) as client:
            await client.reporter.report(
                [
                    {
                        "traceId": trace_id,
                        "id": root_id,
                        "kind": "CLIENT",
                        "name": "async-root",
                        "timestamp": NOW_US,
                        "duration": 2000,
                        "localEndpoint": {"serviceName": ASYNC_SVC},
                        "remoteEndpoint": {"serviceName": BACKEND},
                        "tags": {"grow.test": TAG},
                    },
                    {
                        "traceId": trace_id,
                        "parentId": root_id,
                        "id": child_id,
                        "name": "async-child",
                        "timestamp": NOW_US + 10,
                        "duration": 500,
                        "localEndpoint": {"serviceName": ASYNC_SVC},
                    },
                ]
            )
            gz = client.create_reporter(compression="gzip")
            other_trace = hex_id(16)
            await gz.report(
                [
                    {
                        "traceId": other_trace,
                        "id": hex_id(8),
                        "kind": "CLIENT",
                        "name": "gz",
                        "timestamp": NOW_US,
                        "duration": 100,
                        "localEndpoint": {"serviceName": ASYNC_SVC},
                        # a CLIENT span without a child: the edge comes from remoteEndpoint
                        "remoteEndpoint": {"serviceName": BACKEND},
                    }
                ]
            )
            q = client.query
            spans = await q.get_trace_spans(trace_id)
            assert spans is not None
            assert len(spans) == 2
            trace = await q.get_trace(trace_id)
            assert trace is not None
            assert [s.name for s in trace.spans] == [
                "async-root",
                "async-child",
            ]
            waited = await q.wait_for_trace(trace_id, min_spans=2, interval_s=0.2)
            assert len(waited.spans) == 2
            traces = await q.get_traces(service_name=ASYNC_SVC, tags={"grow.test": TAG})
            assert [t[0].trace_id for t in traces] == [trace_id]
            many = await q.get_trace_many([other_trace, trace_id, hex_id(16)])
            assert [t[0].trace_id for t in many] == [other_trace, trace_id]
            summaries = await q.search_traces(
                sdk.TraceSearchQuery(service=ASYNC_SVC, operation="async-root")
            )
            assert [(s.trace_id, s.span_count, s.root_name) for s in summaries] == [
                (trace_id, 2, "async-root")
            ]
            assert ASYNC_SVC in await q.list_services()
            assert await q.list_spans(ASYNC_SVC) == ["async-child", "async-root", "gz"]
            assert await q.list_operations(ASYNC_SVC) == ["async-child", "async-root", "gz"]
            assert await q.list_remote_services(ASYNC_SVC) == [BACKEND]
            links = await q.get_dependencies(end_ts=now_ms() + 60_000, lookback_s=3600)
            assert any(link.parent == ASYNC_SVC and link.child == BACKEND for link in links)
            assert "grow.test" in await q.autocomplete_keys()
            assert TAG in await q.autocomplete_values("grow.test")
        async with sdk.AsyncZipkinQueryClient.from_env(env) as standalone:
            assert ASYNC_SVC in await standalone.list_services()
        async with sdk.AsyncZipkinReporter.from_env(env) as reporter:
            await reporter.report([])

    asyncio.run(run())
