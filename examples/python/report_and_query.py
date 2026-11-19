"""Report spans without OpenTelemetry (ZipkinReporter) and read them back (ZipkinQueryClient)."""

from __future__ import annotations

import secrets
import time

import grow_zipkin as sdk


def main() -> None:
    trace_id, root_id, child_id = secrets.token_hex(16), secrets.token_hex(8), secrets.token_hex(8)
    now_us = int(time.time() * 1_000_000)
    with sdk.ZipkinClient.from_env() as client:
        client.reporter.report(
            [
                sdk.ZipkinSpan(
                    trace_id=trace_id,
                    id=root_id,
                    kind="SERVER",
                    name="GET /orders",
                    timestamp=now_us,
                    duration=120_000,
                    local_endpoint=sdk.ZipkinEndpoint("example-frontend"),
                    tags={"http.method": "GET", "http.status_code": 200},
                ),
                # The wire form (camelCase mapping) works too.
                {
                    "traceId": trace_id,
                    "parentId": root_id,
                    "id": child_id,
                    "kind": "CLIENT",
                    "name": "select orders",
                    "timestamp": now_us + 5_000,
                    "duration": 80_000,
                    "localEndpoint": {"serviceName": "example-frontend"},
                    "remoteEndpoint": {"serviceName": "example-db"},
                },
            ]
        )
        q = client.query
        trace = q.wait_for_trace(trace_id, min_spans=2, timeout_s=10)
        for span in trace.spans:
            print(f"{span.service_name:>18} {span.kind:<7} {span.name} ({span.duration_micros} µs)")
        print("services:", q.list_services())
        print("span names:", q.list_spans("example-frontend"))
        print("remote services:", q.list_remote_services("example-frontend"))
        print("dependencies:", q.get_dependencies(lookback_s=3600))
        print(
            "GET traces:",
            len(q.get_traces(service_name="example-frontend", tags={"http.method": "GET"})),
        )
        print("summaries:", q.search_traces(sdk.TraceSearchQuery(service="example-frontend")))
        print("autocomplete keys:", q.autocomplete_keys())


if __name__ == "__main__":
    main()
