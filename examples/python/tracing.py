"""OpenTelemetry tracing to Zipkin: native Zipkin v2 JSON export, W3C + B3 propagation."""

from __future__ import annotations

import grow_zipkin as sdk


@sdk.traced("load-order")
def load_order(order_id: str) -> dict[str, str]:
    sdk.add_event("cache.miss", {"order.id": order_id})
    return {"id": order_id}


def main() -> None:
    with sdk.ZipkinClient.from_env() as client:
        # Exports to client.config.endpoint (<url>/api/v2/spans); close() flushes and shuts down.
        handle = client.init_tracing(service_name="example-checkout", service_version="1.0.0")
        with sdk.with_span("checkout", kind="server", attributes={"cart.items": 3}):
            load_order("o-42")
            headers: dict[str, str] = {}
            sdk.inject(headers)  # traceparent + b3 for the next hop
            print("outgoing headers:", headers)
            trace_id = sdk.current_trace_id()
        handle.force_flush()
        trace = client.query.wait_for_trace(str(trace_id), min_spans=2, timeout_s=10)
        print([f"{s.name} ({s.kind})" for s in trace.spans])


if __name__ == "__main__":
    main()
