"""ZipkinSpanExporter — an OpenTelemetry ``SpanExporter`` that sends Zipkin v2 JSON through
:class:`~grow_zipkin.ZipkinReporter` (service-owned; no dependency on the deprecated
OpenTelemetry Zipkin exporters)."""

from __future__ import annotations

import contextlib
import threading
from collections.abc import Callable, Mapping, Sequence
from typing import Any

import httpx
from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.sdk.trace.export import SpanExporter, SpanExportResult
from opentelemetry.trace import SpanKind, StatusCode

from ._core import ConfigError
from .config import ZipkinConfig
from .model import ZipkinAnnotation, ZipkinEndpoint, ZipkinSpan, _json_text, tag_value
from .reporter import DEFAULT_MAX_BATCH_SIZE, ReporterCompression, ZipkinReporter

__all__ = [
    "REMOTE_SERVICE_KEYS",
    "UNKNOWN_SERVICE",
    "ZipkinSpanExporter",
    "attribute_to_tag",
    "to_zipkin_span",
]

#: Service name used when the resource has no ``service.name``.
UNKNOWN_SERVICE = "unknown_service"

#: Span attributes that name the remote service of a CLIENT/PRODUCER span, by precedence.
REMOTE_SERVICE_KEYS: tuple[str, ...] = (
    "peer.service",
    "server.address",
    "net.peer.name",
    "network.peer.address",
)

_KINDS: dict[SpanKind, str] = {
    SpanKind.SERVER: "SERVER",
    SpanKind.CLIENT: "CLIENT",
    SpanKind.PRODUCER: "PRODUCER",
    SpanKind.CONSUMER: "CONSUMER",
}


def attribute_to_tag(value: Any) -> str:
    """Converts an OpenTelemetry attribute value to a Zipkin tag (sequences become JSON)."""
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        return _json_text(list(value))
    return tag_value(value)


def _ns_to_micros(ns: int | None) -> int:
    return 0 if ns is None else ns // 1000


def _remote_service(attributes: Mapping[str, Any]) -> str | None:
    for key in REMOTE_SERVICE_KEYS:
        value = attributes.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def _plain(value: Any) -> Any:
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        return list(value)
    return value


def to_zipkin_span(span: ReadableSpan) -> ZipkinSpan:
    """Maps a finished OpenTelemetry span to a Zipkin v2 span.

    Identical in every language of this SDK (based on the OpenTelemetry Zipkin exporter
    specification):

    ==============================  ===========================================================
    Zipkin                          OpenTelemetry
    ==============================  ===========================================================
    ``traceId``, ``id``             span context ids (lower-case hex, 32 / 16)
    ``parentId``                    parent span id when valid, else omitted
    ``kind``                        SERVER/CLIENT/PRODUCER/CONSUMER 1:1; INTERNAL → omitted
    ``name``                        span name (the server lower-cases it)
    ``timestamp``                   start time, epoch µs (truncated)
    ``duration``                    end - start in µs, rounded, at least 1
    ``localEndpoint.serviceName``   resource ``service.name``, else ``unknown_service``
    ``remoteEndpoint``              CLIENT/PRODUCER only: ``serviceName`` = first non-empty
                                    string of ``peer.service``, ``server.address``,
                                    ``net.peer.name``, ``network.peer.address``
    ``tags``                        span attributes (strings as-is, numbers/booleans as text,
                                    sequences JSON); then ``otel.scope.name`` /
                                    ``otel.scope.version``; ``otel.dropped_attributes_count`` /
                                    ``otel.dropped_events_count`` when > 0; status ERROR →
                                    ``otel.status_code=ERROR`` + ``error=<description or "">``,
                                    OK → ``otel.status_code=OK`` (status tags win). Resource
                                    attributes are not copied
    ``annotations``                 events: ``timestamp`` = event time µs, ``value`` = event
                                    name, or the JSON text ``{"<name>":{…attributes}}`` when the
                                    event has attributes
    ``debug``, ``shared``           never set
    ==============================  ===========================================================

    Links, trace state and resource attributes other than ``service.name`` are not mapped.
    """
    ctx = span.get_span_context()
    if ctx is None:  # pragma: no cover - SDK spans always carry a context
        raise ConfigError("zipkin exporter: span has no context")
    resource_attrs = span.resource.attributes if span.resource is not None else {}
    service = resource_attrs.get("service.name")
    parent = span.parent
    kind = _KINDS.get(span.kind)
    attributes: Mapping[str, Any] = span.attributes or {}
    start = span.start_time or 0
    end = span.end_time or start
    remote: ZipkinEndpoint | None = None
    if kind in ("CLIENT", "PRODUCER"):
        remote_name = _remote_service(attributes)
        if remote_name is not None:
            remote = ZipkinEndpoint(service_name=remote_name)
    annotations = []
    for event in span.events:
        event_attrs = {k: _plain(v) for k, v in (event.attributes or {}).items()}
        value = event.name if not event_attrs else _json_text({event.name: event_attrs})
        annotations.append(ZipkinAnnotation(_ns_to_micros(event.timestamp), value))
    tags = {k: attribute_to_tag(v) for k, v in attributes.items() if v is not None}
    scope = span.instrumentation_scope
    if scope is not None and scope.name:
        tags["otel.scope.name"] = scope.name
        if scope.version:
            tags["otel.scope.version"] = scope.version
    if span.dropped_attributes > 0:
        tags["otel.dropped_attributes_count"] = str(span.dropped_attributes)
    if span.dropped_events > 0:
        tags["otel.dropped_events_count"] = str(span.dropped_events)
    if span.status.status_code is StatusCode.ERROR:
        tags["otel.status_code"] = "ERROR"
        tags["error"] = span.status.description or ""
    elif span.status.status_code is StatusCode.OK:
        tags["otel.status_code"] = "OK"
    return ZipkinSpan(
        trace_id=format(ctx.trace_id, "032x"),
        id=format(ctx.span_id, "016x"),
        parent_id=format(parent.span_id, "016x") if parent is not None and parent.span_id else None,
        kind=kind,
        name=span.name,
        timestamp=_ns_to_micros(start),
        duration=max(1, round((end - start) / 1000)),
        local_endpoint=ZipkinEndpoint(
            service_name=service if isinstance(service, str) and service else UNKNOWN_SERVICE
        ),
        remote_endpoint=remote,
        annotations=annotations,
        tags=tags,
    )


class ZipkinSpanExporter(SpanExporter):
    """OpenTelemetry ``SpanExporter`` for Zipkin.

    Maps each span with :func:`to_zipkin_span` and sends the batch with a
    :class:`~grow_zipkin.ZipkinReporter` (``POST /api/v2/spans``, JSON v2, gzip by default,
    retries on 5xx/429/network errors). A failed batch returns ``FAILURE`` to the span
    processor and is dropped.

    Args:
        reporter: Send through this reporter (not closed by :meth:`shutdown`).
        config: Otherwise: connection settings of an owned reporter (default
            ``ZipkinConfig.from_env()``).
        compression: Request body compression of an owned reporter. Default ``"gzip"``.
        max_batch_size: Spans per POST of an owned reporter. Default 512.
        on_error: Called with every failed export (the batch is dropped). Never raises.
        http_transport: Test seam for an owned reporter.
    """

    def __init__(
        self,
        *,
        reporter: ZipkinReporter | None = None,
        config: ZipkinConfig | None = None,
        compression: ReporterCompression = "gzip",
        max_batch_size: int = DEFAULT_MAX_BATCH_SIZE,
        on_error: Callable[[BaseException], None] | None = None,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if reporter is not None:
            self.reporter = reporter
            self._owned = False
        else:
            self.reporter = ZipkinReporter(
                config if config is not None else ZipkinConfig.from_env(),
                compression=compression,
                max_batch_size=max_batch_size,
                http_transport=http_transport,
            )
            self._owned = True
        self._on_error = on_error
        self._lock = threading.Lock()
        self._shutdown = False

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        """Exports one batch synchronously; ``SUCCESS`` or ``FAILURE``."""
        if self._shutdown:
            return SpanExportResult.FAILURE
        try:
            self.reporter.report([to_zipkin_span(s) for s in spans])
        except Exception as err:  # noqa: BLE001 - reported through on_error
            if self._on_error is not None:
                with contextlib.suppress(Exception):  # hooks never break exporting
                    self._on_error(err)
            return SpanExportResult.FAILURE
        return SpanExportResult.SUCCESS

    def force_flush(self, timeout_millis: int = 30_000) -> bool:
        """Nothing is buffered here (exports are synchronous); always ``True``."""
        return True

    def shutdown(self) -> None:
        """Closes an owned reporter; later exports fail. Idempotent."""
        with self._lock:
            if self._shutdown:
                return
            self._shutdown = True
        if self._owned:
            self.reporter.close()
