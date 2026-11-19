"""The Zipkin v2 JSON span model, ingest normalization and the conversion of Zipkin spans into
the SPEC §5 trace model (service-owned)."""

from __future__ import annotations

import dataclasses
import json
import math
import re
from collections.abc import Iterable, Mapping, Sequence
from decimal import Decimal
from typing import Any, Literal

from ._core import ValidationError
from ._tracing import (
    DependencyLink,
    Span,
    SpanEvent,
    SpanKindName,
    SpanStatus,
    Trace,
    TraceSummary,
    normalize_trace_id,
)

__all__ = [
    "ZipkinKind",
    "ZipkinEndpoint",
    "ZipkinAnnotation",
    "ZipkinSpan",
    "ZipkinSpanInput",
    "ZipkinDependencyLink",
    "ZipkinTraceSpan",
    "zipkin_trace_id",
    "tag_value",
    "normalize_zipkin_span",
    "zipkin_spans_from_body",
    "zipkin_traces_from_body",
    "strings_from_body",
    "dependencies_from_body",
    "annotation_to_event",
    "zipkin_span_to_span",
    "zipkin_trace_to_trace",
    "summarize_zipkin_trace",
]

#: Zipkin span kind; ``None`` means internal (local) work.
ZipkinKind = Literal["CLIENT", "SERVER", "PRODUCER", "CONSUMER"]

_TRACE_ID = re.compile(r"^(?:[0-9a-f]{16}|[0-9a-f]{32})$")
_SPAN_ID = re.compile(r"^[0-9a-f]{16}$")
_KINDS = frozenset(("CLIENT", "SERVER", "PRODUCER", "CONSUMER"))
_KIND_NAMES: dict[str, SpanKindName] = {
    "CLIENT": "client",
    "SERVER": "server",
    "PRODUCER": "producer",
    "CONSUMER": "consumer",
}


def _json_text(value: Any) -> str:
    """Compact JSON like ``JSON.stringify`` (no spaces, non-ASCII kept, integral floats as ints)."""
    return json.dumps(_jsonable(value), separators=(",", ":"), ensure_ascii=False)


def _jsonable(value: Any) -> Any:
    if isinstance(value, bool) or value is None or isinstance(value, (str, int)):
        return value
    if isinstance(value, float):
        if math.isfinite(value) and value == int(value) and abs(value) < 1e21:
            return int(value)
        return value if math.isfinite(value) else None
    if isinstance(value, Mapping):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_jsonable(v) for v in value]
    return str(value)


def _number_text(value: float) -> str:
    if not math.isfinite(value):
        return "NaN" if math.isnan(value) else ("Infinity" if value > 0 else "-Infinity")
    if value == int(value) and abs(value) < 1e21:
        return str(int(value))
    text = repr(value)
    if "e" in text or "E" in text:
        text = format(Decimal(text), "f")
    return text


def tag_value(value: Any) -> str:
    """Converts a tag value to Zipkin's string form.

    Strings as-is, booleans ``"true"``/``"false"``, numbers in decimal (``"7"``, ``"1.5"``,
    ``1.0`` → ``"1"``), ``None`` → ``""``, anything else (lists, dicts) compact JSON.
    """
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return _number_text(value)
    if value is None:
        return ""
    return _json_text(value)


@dataclasses.dataclass(frozen=True)
class ZipkinEndpoint:
    """Network endpoint of a span (``localEndpoint`` / ``remoteEndpoint``).

    ``service_name`` is lower-cased by the server on ingest.
    """

    service_name: str | None = None
    ipv4: str | None = None
    ipv6: str | None = None
    port: int | None = None

    def to_json(self) -> dict[str, Any]:
        """Wire form (camelCase, empty fields omitted)."""
        out: dict[str, Any] = {}
        if self.service_name:
            out["serviceName"] = self.service_name
        if self.ipv4:
            out["ipv4"] = self.ipv4
        if self.ipv6:
            out["ipv6"] = self.ipv6
        if self.port:
            out["port"] = self.port
        return out

    @classmethod
    def from_json(cls, data: Any) -> ZipkinEndpoint | None:
        """Parse the wire form; ``None`` when ``data`` is not an object."""
        if isinstance(data, ZipkinEndpoint):
            return data
        if not isinstance(data, Mapping):
            return None
        port = data.get("port")
        return cls(
            service_name=_opt_str(data.get("serviceName")),
            ipv4=_opt_str(data.get("ipv4")),
            ipv6=_opt_str(data.get("ipv6")),
            port=port if isinstance(port, int) and not isinstance(port, bool) else None,
        )


@dataclasses.dataclass(frozen=True)
class ZipkinAnnotation:
    """A timestamped event on a span (``timestamp`` in epoch microseconds)."""

    timestamp: int
    value: str

    def to_json(self) -> dict[str, Any]:
        return {"timestamp": self.timestamp, "value": self.value}


@dataclasses.dataclass(frozen=True)
class ZipkinSpan:
    """A Zipkin v2 span.

    As returned by the server every tag value is a string, ids are lower-case hex and names are
    lower-case. As input of :meth:`ZipkinReporter.report <grow_zipkin.ZipkinReporter.report>`
    ids may be upper-case, ``kind`` lower-case and tag values numbers or booleans
    (:func:`normalize_zipkin_span` converts them).

    Attributes:
        trace_id: 16 or 32 hex characters.
        id: Span id, 16 hex characters.
        parent_id: Parent span id; ``None`` on a root span.
        kind: ``CLIENT``, ``SERVER``, ``PRODUCER``, ``CONSUMER`` or ``None`` (internal).
        name: Span name.
        timestamp: Start, epoch microseconds.
        duration: Duration in microseconds.
        local_endpoint: The service that recorded the span.
        remote_endpoint: The other side of a CLIENT/SERVER/PRODUCER/CONSUMER span.
        annotations: Timestamped events.
        tags: String tags.
        debug: Force-store this span (B3 debug).
        shared: A server span that shares its id with the client span.
    """

    trace_id: str
    id: str
    parent_id: str | None = None
    kind: str | None = None
    name: str | None = None
    timestamp: int | float | None = None
    duration: int | float | None = None
    local_endpoint: ZipkinEndpoint | None = None
    remote_endpoint: ZipkinEndpoint | None = None
    annotations: Sequence[ZipkinAnnotation] = ()
    tags: Mapping[str, Any] = dataclasses.field(default_factory=dict)
    debug: bool = False
    shared: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "annotations", tuple(self.annotations))
        object.__setattr__(self, "tags", dict(self.tags))

    def to_json(self) -> dict[str, Any]:
        """Wire form (camelCase, empty fields omitted, key order as Zipkin returns it)."""
        out: dict[str, Any] = {"traceId": self.trace_id}
        if self.parent_id:
            out["parentId"] = self.parent_id
        out["id"] = self.id
        if self.kind:
            out["kind"] = self.kind
        if self.name:
            out["name"] = self.name
        if self.timestamp:
            out["timestamp"] = self.timestamp
        if self.duration:
            out["duration"] = self.duration
        for key, endpoint in (
            ("localEndpoint", self.local_endpoint),
            ("remoteEndpoint", self.remote_endpoint),
        ):
            if endpoint is not None and endpoint.to_json():
                out[key] = endpoint.to_json()
        if self.annotations:
            out["annotations"] = [a.to_json() for a in self.annotations]
        if self.tags:
            out["tags"] = {k: tag_value(v) for k, v in self.tags.items()}
        if self.debug:
            out["debug"] = True
        if self.shared:
            out["shared"] = True
        return out

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> ZipkinSpan:
        """Parse the wire form (``ValidationError`` when ``traceId``/``id`` are missing)."""
        trace_id, span_id = data.get("traceId"), data.get("id")
        if not isinstance(trace_id, str) or not isinstance(span_id, str):
            raise ValidationError("zipkin span: traceId and id are required strings")
        annotations = []
        for a in data.get("annotations") or ():
            if isinstance(a, ZipkinAnnotation):
                annotations.append(a)
            elif isinstance(a, Mapping):
                annotations.append(ZipkinAnnotation(a.get("timestamp"), a.get("value")))  # type: ignore[arg-type]
            else:
                annotations.append(a)
        tags = data.get("tags")
        return cls(
            trace_id=trace_id,
            id=span_id,
            parent_id=_opt_str(data.get("parentId")),
            kind=_opt_str(data.get("kind")),
            name=_opt_str(data.get("name")),
            timestamp=data.get("timestamp"),
            duration=data.get("duration"),
            local_endpoint=ZipkinEndpoint.from_json(data.get("localEndpoint")),
            remote_endpoint=ZipkinEndpoint.from_json(data.get("remoteEndpoint")),
            annotations=annotations,
            tags=dict(tags) if isinstance(tags, Mapping) else {},
            debug=data.get("debug") is True,
            shared=data.get("shared") is True,
        )


#: Input of :meth:`ZipkinReporter.report <grow_zipkin.ZipkinReporter.report>`: a
#: :class:`ZipkinSpan` or its camelCase wire form as a mapping.
ZipkinSpanInput = ZipkinSpan | Mapping[str, Any]


@dataclasses.dataclass(frozen=True)
class ZipkinDependencyLink(DependencyLink):
    """A dependency edge with Zipkin's ``errorCount`` (0 when the server omits it)."""

    error_count: int = 0


@dataclasses.dataclass(frozen=True)
class ZipkinTraceSpan(Span):
    """A span of the SPEC §5 model with the Zipkin-only fields kept."""

    local_endpoint: ZipkinEndpoint | None = None
    remote_endpoint: ZipkinEndpoint | None = None
    shared: bool = False
    debug: bool = False


def _opt_str(value: Any) -> str | None:
    return value if isinstance(value, str) and value != "" else None


def zipkin_trace_id(trace_id: str, operation: str = "zipkin") -> str:
    """Lower-cases and validates a Zipkin trace id (16 or 32 hex characters, not all zero).

    Raises :class:`ValidationError` without a request otherwise.
    """
    lower = trace_id.strip().lower() if isinstance(trace_id, str) else ""
    if not _TRACE_ID.match(lower) or set(lower) == {"0"}:
        raise ValidationError(f"{operation}: trace id must be 16 or 32 hex characters")
    return lower


def _span_id(value: Any, what: str, operation: str) -> str:
    lower = value.strip().lower() if isinstance(value, str) else ""
    if not _SPAN_ID.match(lower) or set(lower) == {"0"}:
        raise ValidationError(f"{operation}: {what} must be 16 hex characters")
    return lower


def _micros(value: Any, what: str, operation: str) -> int | None:
    if value is None:
        return None
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
    ):
        raise ValidationError(f"{operation}: {what} must be a non-negative number of microseconds")
    return round(value)


def _check_raw_endpoint(value: Any, operation: str) -> None:
    if value is None:
        return
    if not isinstance(value, Mapping):
        raise ValidationError(f"{operation}: endpoint must be an object")
    port = value.get("port")
    if port is not None and (isinstance(port, bool) or not isinstance(port, int)):
        raise ValidationError(f"{operation}: endpoint port must be 0..65535")


def _endpoint(endpoint: ZipkinEndpoint | None, operation: str) -> ZipkinEndpoint | None:
    if endpoint is None:
        return None
    port = endpoint.port
    if port is not None and not 0 <= port <= 65535:
        raise ValidationError(f"{operation}: endpoint port must be 0..65535")
    out = ZipkinEndpoint(
        service_name=endpoint.service_name or None,
        ipv4=endpoint.ipv4 or None,
        ipv6=endpoint.ipv6 or None,
        port=port or None,
    )
    return None if out == ZipkinEndpoint() else out


def normalize_zipkin_span(span: ZipkinSpanInput, operation: str = "zipkin.report") -> ZipkinSpan:
    """Normalizes one span for ingest.

    Applies the rules Zipkin enforces locally, so one bad span never makes the server reject
    the whole batch: ids lower-cased and validated, ``kind`` upper-cased, timestamps rounded to
    integer microseconds (0 dropped), tag values converted to strings (``None`` dropped), empty
    fields dropped. Raises :class:`ValidationError` naming the problem.
    """
    if isinstance(span, Mapping):
        for key in ("localEndpoint", "remoteEndpoint"):
            _check_raw_endpoint(span.get(key), operation)
        span = ZipkinSpan.from_json(span) if _has_ids(span) else _missing_ids(operation)
    elif not isinstance(span, ZipkinSpan):
        raise ValidationError(f"{operation}: span must be a ZipkinSpan or a mapping")
    trace_id = zipkin_trace_id(span.trace_id, operation)
    span_id = _span_id(span.id, "span id", operation)
    parent = _span_id(span.parent_id, "parentId", operation) if span.parent_id else None
    kind: str | None = None
    if span.kind is not None:
        kind = span.kind.upper() if isinstance(span.kind, str) else ""
        if kind not in _KINDS:
            raise ValidationError(f"{operation}: kind must be CLIENT, SERVER, PRODUCER or CONSUMER")
    timestamp = _micros(span.timestamp, "timestamp", operation)
    duration = _micros(span.duration, "duration", operation)
    annotations = []
    for a in span.annotations:
        value = getattr(a, "value", None)
        if not isinstance(value, str) or value == "":
            raise ValidationError(f"{operation}: annotation value must be a non-empty string")
        ts = _micros(getattr(a, "timestamp", None), "annotation timestamp", operation)
        if not ts:
            raise ValidationError(f"{operation}: annotation timestamp is required")
        annotations.append(ZipkinAnnotation(ts, value))
    tags = {str(k): tag_value(v) for k, v in span.tags.items() if v is not None}
    return ZipkinSpan(
        trace_id=trace_id,
        id=span_id,
        parent_id=parent,
        kind=kind,
        name=span.name or None,
        timestamp=timestamp or None,
        duration=duration or None,
        local_endpoint=_endpoint(span.local_endpoint, operation),
        remote_endpoint=_endpoint(span.remote_endpoint, operation),
        annotations=annotations,
        tags=tags,
        debug=span.debug is True,
        shared=span.shared is True,
    )


def _has_ids(data: Mapping[str, Any]) -> bool:
    return isinstance(data.get("traceId"), str) and isinstance(data.get("id"), str)


def _missing_ids(operation: str) -> ZipkinSpan:
    raise ValidationError(f"{operation}: span needs traceId and id")


# --------------------------------------------------------------------------- decoding


def zipkin_spans_from_body(body: Any) -> list[ZipkinSpan]:
    """Keeps the well-formed spans of a response body (``ZipkinSpan[]``)."""
    if not isinstance(body, list):
        return []
    return [ZipkinSpan.from_json(s) for s in body if isinstance(s, Mapping) and _has_ids(s)]


def zipkin_traces_from_body(body: Any) -> list[list[ZipkinSpan]]:
    """Keeps the well-formed traces of a response body (``ZipkinSpan[][]``), dropping empty ones."""
    if not isinstance(body, list):
        return []
    traces = (zipkin_spans_from_body(t) for t in body)
    return [t for t in traces if t]


def strings_from_body(body: Any) -> list[str]:
    """Decodes a string list response (``string[]``)."""
    return [s for s in body if isinstance(s, str)] if isinstance(body, list) else []


def _count(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


def dependencies_from_body(body: Any) -> list[ZipkinDependencyLink]:
    """Decodes ``/api/v2/dependencies``: ``errorCount`` defaults to 0, sorted by parent, child."""
    if not isinstance(body, list):
        return []
    links = [
        ZipkinDependencyLink(
            parent=link["parent"],
            child=link["child"],
            call_count=_count(link.get("callCount")),
            error_count=_count(link.get("errorCount")),
        )
        for link in body
        if isinstance(link, Mapping)
        and isinstance(link.get("parent"), str)
        and isinstance(link.get("child"), str)
    ]
    return sorted(links, key=lambda link: (link.parent, link.child))


# --------------------------------------------------------------------------- Zipkin → SPEC §5


def annotation_to_event(annotation: ZipkinAnnotation) -> SpanEvent:
    """Decodes an annotation into an event.

    A value of the form ``{"<name>": {…attributes}}`` (how
    :class:`~grow_zipkin.ZipkinSpanExporter` encodes an event with attributes) becomes that name
    and attributes; any other value is the event name with no attributes.
    """
    value = annotation.value
    if value.startswith("{") and value.endswith("}"):
        try:
            parsed = json.loads(value)
        except ValueError:
            parsed = None
        if isinstance(parsed, dict) and len(parsed) == 1:
            ((name, attributes),) = parsed.items()
            if isinstance(attributes, dict):
                return SpanEvent(name, annotation.timestamp, attributes)
    return SpanEvent(value, annotation.timestamp, {})


def _status(tags: Mapping[str, Any]) -> SpanStatus:
    error = tags.get("error")
    otel_status = str(tags.get("otel.status_code", "")).upper()
    if error is not None:
        return SpanStatus("error", str(error) or None)
    if otel_status == "ERROR":
        return SpanStatus("error")
    if otel_status == "OK":
        return SpanStatus("ok")
    return SpanStatus()


def zipkin_span_to_span(span: ZipkinSpan) -> ZipkinTraceSpan:
    """Converts a Zipkin span into the SPEC §5 model.

    Trace id padded to 32 characters, ``kind`` lower-cased (absent → ``internal``),
    ``service_name`` = ``local_endpoint.service_name`` (or ``""``), ``attributes`` = tags,
    annotations → events, status from the ``error`` tag (error, message = its value) or
    ``otel.status_code`` (``OK``/``ERROR``), no links. Endpoints, ``shared`` and ``debug`` are
    kept.
    """
    start = int(span.timestamp or 0)
    duration = int(span.duration or 0)
    kind: SpanKindName = (
        "internal" if span.kind is None else _KIND_NAMES.get(span.kind.upper(), "unspecified")
    )
    local = span.local_endpoint
    return ZipkinTraceSpan(
        trace_id=normalize_trace_id(span.trace_id),
        span_id=span.id.lower(),
        parent_span_id=span.parent_id.lower() if span.parent_id else None,
        name=span.name or "",
        kind=kind,
        service_name=(local.service_name if local is not None else None) or "",
        start_micros=start,
        end_micros=start + duration,
        duration_micros=duration,
        status=_status(span.tags),
        attributes=dict(span.tags),
        events=[annotation_to_event(a) for a in span.annotations],
        links=(),
        local_endpoint=span.local_endpoint,
        remote_endpoint=span.remote_endpoint,
        shared=span.shared,
        debug=span.debug,
    )


def zipkin_trace_to_trace(spans: Iterable[ZipkinSpan]) -> Trace | None:
    """Converts the spans of one Zipkin trace into a :class:`Trace` (sorted); ``None`` if empty."""
    items = list(spans)
    if not items:
        return None
    return Trace(
        trace_id=normalize_trace_id(items[0].trace_id),
        spans=[zipkin_span_to_span(s) for s in items],
    )


def summarize_zipkin_trace(spans: Iterable[ZipkinSpan]) -> TraceSummary | None:
    """Summarises one Zipkin trace (root = the span without a parent in the trace, else the
    earliest); ``None`` when empty."""
    trace = zipkin_trace_to_trace(spans)
    return None if trace is None else TraceSummary.from_trace(trace)
