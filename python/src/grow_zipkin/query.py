"""ZipkinQueryClient — the Zipkin v2 query API and the SPEC §5 TraceQueryClient (service-owned)."""

from __future__ import annotations

import dataclasses
import math
import time
from collections.abc import Callable, Iterable, Mapping, Sequence
from datetime import datetime, timedelta, timezone
from types import TracebackType
from typing import TYPE_CHECKING, Any

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    HttpTransport,
    Request,
    Response,
    ValidationError,
)
from ._core.env import to_seconds
from ._http import asend, json_body, send
from ._tracing import (
    AsyncBaseTraceQueryClient,
    BaseTraceQueryClient,
    Trace,
    TraceSearchQuery,
    TraceSummary,
)
from .config import ZipkinConfig
from .model import (
    ZipkinDependencyLink,
    ZipkinSpan,
    dependencies_from_body,
    strings_from_body,
    summarize_zipkin_trace,
    zipkin_spans_from_body,
    zipkin_trace_id,
    zipkin_trace_to_trace,
    zipkin_traces_from_body,
)

if TYPE_CHECKING:
    from .client import AsyncZipkinClient, ZipkinClient

__all__ = [
    "DEFAULT_SEARCH_LIMIT",
    "DEFAULT_SEARCH_WINDOW_S",
    "TRACE_MANY_CHUNK",
    "AsyncZipkinQueryClient",
    "ZipkinQueryClient",
    "ZipkinTraceQuery",
    "build_annotation_query",
]

#: Default ``limit`` of ``search_traces`` (SPEC §5).
DEFAULT_SEARCH_LIMIT = 20
#: Window of ``search_traces`` without ``start``: the last hour before ``end``.
DEFAULT_SEARCH_WINDOW_S = 3600.0
#: Most trace ids sent in one ``/api/v2/traceMany`` request: 100 ids of 33 characters keeps the
#: request line under Armeria's default HTTP/1 limit of 4096 bytes.
TRACE_MANY_CHUNK = 100

_AND = " and "


@dataclasses.dataclass(frozen=True)
class ZipkinTraceQuery:
    """Filter of :meth:`ZipkinQueryClient.get_traces` (``GET /api/v2/traces``).

    Every field is optional; unset fields use the server defaults (``end_ts`` = now,
    ``lookback`` = 24 h, ``limit`` = 10). ``annotation_query``, ``tags`` and ``annotations`` are
    combined with `` and `` (Zipkin supports AND only).

    Attributes:
        service_name: Local service name (matched case-insensitively).
        remote_service_name: ``remoteEndpoint.serviceName`` seen on ``service_name``'s spans.
        span_name: Span name.
        annotation_query: Raw Zipkin annotation query, e.g. ``http.method=GET and error``.
        tags: Exact tag matches (``key=value``). A key or value that contains `` and `` (or a
            key that contains ``=``) cannot be expressed in Zipkin's syntax: that term is matched
            client-side after the query (so fewer than ``limit`` traces may come back).
        annotations: Bare terms: a tag key or an annotation value (``error``, ``cache.miss``).
        min_duration_micros: Minimum span duration in microseconds.
        max_duration_micros: Maximum span duration (needs ``min_duration_micros``).
        end_ts: End of the window: a ``datetime`` or epoch milliseconds. Default: server now.
        lookback_s: Window length before ``end_ts`` (seconds or ``timedelta``). Default: the
            server's ``QUERY_LOOKBACK`` (24 h).
        limit: Maximum number of traces. Default: the server default (10).
    """

    service_name: str | None = None
    remote_service_name: str | None = None
    span_name: str | None = None
    annotation_query: str | None = None
    tags: Mapping[str, str] = dataclasses.field(default_factory=dict)
    annotations: Sequence[str] = ()
    min_duration_micros: int | None = None
    max_duration_micros: int | None = None
    end_ts: datetime | int | None = None
    lookback_s: float | timedelta | None = None
    limit: int | None = None


@dataclasses.dataclass(frozen=True)
class _Term:
    key: str
    value: str | None = None

    @property
    def server_side(self) -> bool:
        return _AND not in self.key and "=" not in self.key and _AND not in (self.value or "")

    @property
    def text(self) -> str:
        return self.key if self.value is None else f"{self.key}={self.value}"

    def matches(self, spans: Sequence[ZipkinSpan]) -> bool:
        for span in spans:
            if self.value is not None:
                if span.tags.get(self.key) == self.value:
                    return True
            elif self.key in span.tags or any(a.value == self.key for a in span.annotations):
                return True
        return False


def _terms(tags: Mapping[str, str], annotations: Iterable[str]) -> list[_Term]:
    return [_Term(k, v) for k, v in tags.items()] + [_Term(k) for k in annotations]


def build_annotation_query(
    tags: Mapping[str, str] | None = None, annotations: Iterable[str] = ()
) -> str | None:
    """Builds a Zipkin ``annotationQuery`` from exact tag matches and bare terms (joined with
    `` and ``); ``None`` when there are none.

    Raises :class:`ValidationError` for a term Zipkin cannot express (contains `` and ``, or a
    key with ``=``).
    """
    terms = _terms(tags or {}, annotations)
    for term in terms:
        if term.key == "":
            raise ValidationError("zipkin.annotationQuery: empty term")
        if not term.server_side:
            raise ValidationError(
                'zipkin.annotationQuery: a term must not contain " and " (or "=" in a key)'
            )
    return _AND.join(t.text for t in terms) if terms else None


# --------------------------------------------------------------------------- helpers


def _epoch_ms(value: datetime | int | float, what: str, operation: str) -> int:
    if isinstance(value, datetime):
        aware = value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)
        ms: float = aware.timestamp() * 1000.0
    elif isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{operation}: {what} must be a datetime or epoch milliseconds")
    else:
        ms = float(value)
    if not math.isfinite(ms) or ms <= 0:
        raise ValidationError(f"{operation}: {what} must be a positive time")
    return round(ms)


def _duration_ms(value: float | timedelta, what: str, operation: str) -> int:
    try:
        seconds = to_seconds(value)
    except (TypeError, ValueError):
        raise ValidationError(f"{operation}: {what} must be a duration") from None
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValidationError(f"{operation}: {what} must be positive")
    return max(1, round(seconds * 1000.0))


def _non_empty(value: str, what: str, operation: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{operation}: {what} is required")
    return value


def _blank_to_none(value: str | None) -> str | None:
    return None if value is None or not value.strip() else value


def _micros_value(value: int | None, what: str, operation: str) -> int | None:
    if value is None:
        return None
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
    ):
        raise ValidationError(f"{operation}: {what} must be a non-negative number")
    return round(value)


def _padded(trace_id: str) -> str:
    return trace_id.lower().rjust(32, "0")


@dataclasses.dataclass
class _TracesPlan:
    request: Request
    local: list[_Term]

    def result(self, resp: Response) -> list[list[ZipkinSpan]]:
        traces = zipkin_traces_from_body(json_body(resp))
        if not self.local:
            return traces
        return [t for t in traces if all(term.matches(t) for term in self.local)]


def _traces_plan(query: ZipkinTraceQuery) -> _TracesPlan:
    op = "zipkin.getTraces"
    min_d = _micros_value(query.min_duration_micros, "min_duration_micros", op)
    max_d = _micros_value(query.max_duration_micros, "max_duration_micros", op)
    if max_d is not None and min_d is None:
        raise ValidationError(f"{op}: max_duration_micros needs min_duration_micros")
    if max_d is not None and min_d is not None and max_d < min_d:
        raise ValidationError(f"{op}: max_duration_micros must not be below min_duration_micros")
    if query.limit is not None and (
        isinstance(query.limit, bool) or not isinstance(query.limit, int) or query.limit <= 0
    ):
        raise ValidationError(f"{op}: limit must be a positive integer")
    lookback = (
        None if query.lookback_s is None else _duration_ms(query.lookback_s, "lookback_s", op)
    )
    terms = _terms(query.tags, query.annotations)
    if any(t.key == "" for t in terms):
        raise ValidationError(f"{op}: tag keys and annotations must not be empty")
    parts = []
    if query.annotation_query is not None and query.annotation_query.strip():
        parts.append(query.annotation_query.strip())
    parts.extend(t.text for t in terms if t.server_side)
    params = {
        "serviceName": _blank_to_none(query.service_name),
        "remoteServiceName": _blank_to_none(query.remote_service_name),
        "spanName": _blank_to_none(query.span_name),
        "annotationQuery": _AND.join(parts) if parts else None,
        "minDuration": min_d,
        "maxDuration": max_d,
        "endTs": None if query.end_ts is None else _epoch_ms(query.end_ts, "end_ts", op),
        "lookback": lookback,
        "limit": query.limit,
    }
    return _TracesPlan(
        Request("GET", "/api/v2/traces", operation=op, params=params),
        [t for t in terms if not t.server_side],
    )


def _trace_request(trace_id: str) -> Request:
    tid = zipkin_trace_id(trace_id, "zipkin.getTrace")
    return Request("GET", f"/api/v2/trace/{tid}", operation=f"zipkin.getTrace {tid}")


def _trace_spans(resp: Response) -> list[ZipkinSpan] | None:
    if resp.status == 404:
        return None
    spans = zipkin_spans_from_body(json_body(resp))
    return spans or None


def _many_ids(trace_ids: Iterable[str]) -> list[str]:
    op = "zipkin.getTraceMany"
    if isinstance(trace_ids, str):
        raise ValidationError(f"{op}: trace_ids must be a list of ids")
    ids: list[str] = []
    seen: set[str] = set()
    for raw in trace_ids:
        tid = zipkin_trace_id(raw, op)
        key = _padded(tid)
        if key not in seen:
            seen.add(key)
            ids.append(tid)
    return ids


def _many_request(chunk: Sequence[str]) -> Request:
    return Request(
        "GET",
        "/api/v2/traceMany",
        operation="zipkin.getTraceMany",
        params={"traceIds": ",".join(chunk)},
    )


def _ordered(ids: Sequence[str], by_id: Mapping[str, list[ZipkinSpan]]) -> list[list[ZipkinSpan]]:
    return [by_id[_padded(i)] for i in ids if _padded(i) in by_id]


def _search_native(query: TraceSearchQuery | None, now: Callable[[], float]) -> ZipkinTraceQuery:
    op = "zipkin.searchTraces"
    q = query or TraceSearchQuery()
    end_ms = _epoch_ms(q.end if q.end is not None else now() * 1000.0, "end", op)
    if q.start is not None:
        start_ms = _epoch_ms(q.start, "start", op)
        if start_ms > end_ms:
            raise ValidationError(f"{op}: start must not be after end")
        lookback_ms = max(1, end_ms - start_ms)
    else:
        lookback_ms = round(DEFAULT_SEARCH_WINDOW_S * 1000)
    return ZipkinTraceQuery(
        service_name=q.service,
        span_name=q.operation,
        tags=dict(q.tags),
        min_duration_micros=q.min_duration_micros,
        max_duration_micros=q.max_duration_micros,
        end_ts=end_ms,
        lookback_s=lookback_ms / 1000.0,
        limit=q.limit if q.limit is not None else DEFAULT_SEARCH_LIMIT,
    )


def _summaries(traces: Iterable[Sequence[ZipkinSpan]]) -> list[TraceSummary]:
    out = []
    for t in traces:
        summary = summarize_zipkin_trace(t)
        if summary is not None:
            out.append(summary)
    return out


def _dependencies_request(
    start: datetime | None,
    end: datetime | None,
    end_ts: datetime | int | None,
    lookback_s: float | timedelta | None,
    now: Callable[[], float],
) -> Request:
    op = "zipkin.getDependencies"
    end_value: datetime | int | float
    if end_ts is not None:
        end_value = end_ts
    elif end is not None:
        end_value = end
    else:
        end_value = now() * 1000.0
    end_ms = _epoch_ms(end_value, "end_ts", op)
    lookback: int | None = None
    if lookback_s is not None:
        lookback = _duration_ms(lookback_s, "lookback", op)
    elif start is not None:
        lookback = end_ms - _epoch_ms(start, "start", op)
        if lookback <= 0:
            raise ValidationError(f"{op}: lookback must be a positive duration")
    return Request(
        "GET", "/api/v2/dependencies", operation=op, params={"endTs": end_ms, "lookback": lookback}
    )


def _names_request(path: str, operation: str, param: str | None = None, value: str = "") -> Request:
    params = None if param is None else {param: _non_empty(value, param, operation)}
    return Request("GET", path, operation=operation, params=params)


def _merge_query(query: ZipkinTraceQuery | None, filters: Mapping[str, Any]) -> ZipkinTraceQuery:
    base = query or ZipkinTraceQuery()
    return dataclasses.replace(base, **filters) if filters else base


# --------------------------------------------------------------------------- sync


class ZipkinQueryClient(BaseTraceQueryClient):
    """Reads traces back from Zipkin (port 9411), synchronously.

    The native v2 query API (:meth:`get_trace_spans`, :meth:`get_traces`,
    :meth:`get_trace_many`, :meth:`list_services`, :meth:`list_spans`,
    :meth:`list_remote_services`, :meth:`get_dependencies`, :meth:`autocomplete_keys`,
    :meth:`autocomplete_values`) and the SPEC §5 ``TraceQueryClient`` (:meth:`get_trace`,
    :meth:`search_traces`, :meth:`list_operations`, :meth:`wait_for_trace`) on top of it. Every
    call is a GET (retried on 5xx, 429 and network errors) and accepts ``cancel=``.

    Get one from :attr:`ZipkinClient.query <grow_zipkin.ZipkinClient.query>` (shares the
    client's connections) or construct it from a config (owns its connections; call
    :meth:`close`).

    Args:
        source: A :class:`~grow_zipkin.ZipkinClient`, a :class:`~grow_zipkin.ZipkinConfig` or
            ``None`` (``ZipkinConfig.from_env()``).
        http_transport: Test seam (an ``httpx`` transport) for an owned transport.
        clock: Wall clock in epoch seconds (test seam; default ``time.time``).
    """

    def __init__(
        self,
        source: ZipkinClient | ZipkinConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        from .client import ZipkinClient  # noqa: PLC0415 - import cycle

        self._now = clock
        if isinstance(source, ZipkinClient):
            self.config = source.config
            self._transport = source.transport
            self._owned = False
        else:
            self.config = (source if source is not None else ZipkinConfig.from_env()).validate()
            self._transport = HttpTransport(
                self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
            )
            self._owned = True

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> ZipkinQueryClient:
        """A standalone query client from ``GROW_ZIPKIN_*`` / ``ZIPKIN_URL``."""
        return cls(ZipkinConfig.from_env(env, **overrides))

    def _send(
        self, req: Request, cancel: CancelToken | None, allow: Sequence[int] = ()
    ) -> Response:
        return send(self._transport, self.config, req, cancel=cancel, allow=allow)

    def get_trace_spans(
        self, trace_id: str, *, cancel: CancelToken | None = None
    ) -> list[ZipkinSpan] | None:
        """One trace in the native model: ``GET /api/v2/trace/{traceId}``.

        Returns ``None`` when the trace does not exist (404). The id is lower-cased; anything but
        16 or 32 hex characters raises :class:`ValidationError` without a request. Spans come
        back in storage order.
        """
        return _trace_spans(self._send(_trace_request(trace_id), cancel, (404,)))

    def get_trace(self, trace_id: str, *, cancel: CancelToken | None = None) -> Trace | None:
        """One trace in the SPEC §5 model (see :func:`~grow_zipkin.zipkin_span_to_span`);
        ``None`` when it does not exist."""
        spans = self.get_trace_spans(trace_id, cancel=cancel)
        return None if spans is None else zipkin_trace_to_trace(spans)

    def get_traces(
        self,
        query: ZipkinTraceQuery | None = None,
        /,
        *,
        cancel: CancelToken | None = None,
        **filters: Any,
    ) -> list[list[ZipkinSpan]]:
        """Searches traces: ``GET /api/v2/traces``, newest first, one span list per trace.

        Pass a :class:`ZipkinTraceQuery` and/or its fields as keywords (keywords win). No match
        returns ``[]``. Invalid filters (``limit <= 0``, ``max_duration_micros`` without or below
        ``min_duration_micros``, non-positive ``end_ts``/``lookback_s``) raise
        :class:`ValidationError` without a request.
        """
        plan = _traces_plan(_merge_query(query, filters))
        return plan.result(self._send(plan.request, cancel))

    def get_trace_many(
        self, trace_ids: Iterable[str], *, cancel: CancelToken | None = None
    ) -> list[list[ZipkinSpan]]:
        """Several traces by id: ``GET /api/v2/traceMany``.

        Ids are lower-cased and de-duplicated (a 16-character id equals its zero-padded
        32-character form); an empty list returns ``[]`` without a request, a single id uses
        ``GET /api/v2/trace/{id}``, more are fetched in chunks of :data:`TRACE_MANY_CHUNK`.
        Missing traces are omitted; the result follows the request order.
        """
        ids = _many_ids(trace_ids)
        if not ids:
            return []
        by_id: dict[str, list[ZipkinSpan]] = {}
        for i in range(0, len(ids), TRACE_MANY_CHUNK):
            chunk = ids[i : i + TRACE_MANY_CHUNK]
            if len(chunk) == 1:
                one = self.get_trace_spans(chunk[0], cancel=cancel)
                traces = [] if one is None else [one]
            else:
                traces = zipkin_traces_from_body(
                    json_body(self._send(_many_request(chunk), cancel))
                )
            for t in traces:
                by_id[_padded(t[0].trace_id)] = t
        return _ordered(ids, by_id)

    def search_traces(
        self, query: TraceSearchQuery | None = None, *, cancel: CancelToken | None = None
    ) -> list[TraceSummary]:
        """SPEC §5 search.

        ``service`` → ``serviceName``, ``operation`` → ``spanName``, ``tags`` → exact tag terms
        (see :attr:`ZipkinTraceQuery.tags`), durations in µs, ``endTs`` = ``end`` (default now),
        ``lookback`` = ``end - start`` (the last hour without ``start``), ``limit`` default 20.
        Returns one summary per trace (root = the span without a parent, else the earliest).
        """
        return _summaries(self.get_traces(_search_native(query, self._now), cancel=cancel))

    def list_services(self, *, cancel: CancelToken | None = None) -> list[str]:
        """Service names that reported spans: ``GET /api/v2/services`` (sorted, lower-case)."""
        req = _names_request("/api/v2/services", "zipkin.listServices")
        return sorted(strings_from_body(json_body(self._send(req, cancel))))

    def list_spans(self, service_name: str, *, cancel: CancelToken | None = None) -> list[str]:
        """Span names of a service: ``GET /api/v2/spans?serviceName=`` (sorted; an unknown
        service gives ``[]``)."""
        req = _names_request("/api/v2/spans", "zipkin.listSpans", "serviceName", service_name)
        return sorted(strings_from_body(json_body(self._send(req, cancel))))

    def list_operations(self, service: str, *, cancel: CancelToken | None = None) -> list[str]:
        """SPEC §5 alias of :meth:`list_spans`."""
        return self.list_spans(service, cancel=cancel)

    def list_remote_services(
        self, service_name: str, *, cancel: CancelToken | None = None
    ) -> list[str]:
        """Remote services called by a service (``remoteEndpoint.serviceName`` on its spans):
        ``GET /api/v2/remoteServices?serviceName=`` (sorted)."""
        req = _names_request(
            "/api/v2/remoteServices", "zipkin.listRemoteServices", "serviceName", service_name
        )
        return sorted(strings_from_body(json_body(self._send(req, cancel))))

    def get_dependencies(  # type: ignore[override]
        self,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        end_ts: datetime | int | None = None,
        lookback_s: float | timedelta | None = None,
        cancel: CancelToken | None = None,
    ) -> list[ZipkinDependencyLink]:
        """Service dependency edges: ``GET /api/v2/dependencies?endTs=&lookback=``.

        The window is either the category window (``start``/``end``) or Zipkin's native
        ``end_ts`` (datetime or epoch ms) + ``lookback_s`` (these win). ``end`` defaults to now;
        without ``start``/``lookback_s`` the server default (24 h) applies. Sorted by parent and
        child, ``error_count`` defaulting to 0. In-memory storage computes edges on read;
        persistent backends need the zipkin-dependencies job, so ``[]`` is normal there.
        """
        req = _dependencies_request(start, end, end_ts, lookback_s, self._now)
        return dependencies_from_body(json_body(self._send(req, cancel)))

    def autocomplete_keys(self, *, cancel: CancelToken | None = None) -> list[str]:
        """Tag keys the server indexes for autocomplete: ``GET /api/v2/autocompleteKeys`` (the
        server's ``AUTOCOMPLETE_KEYS`` in configured order, ``[]`` when unset)."""
        req = _names_request("/api/v2/autocompleteKeys", "zipkin.autocompleteKeys")
        return strings_from_body(json_body(self._send(req, cancel)))

    def autocomplete_values(self, key: str, *, cancel: CancelToken | None = None) -> list[str]:
        """Distinct values seen for an autocomplete key: ``GET /api/v2/autocompleteValues?key=``
        (sorted; ``[]`` for a key that is not in ``AUTOCOMPLETE_KEYS``)."""
        req = _names_request("/api/v2/autocompleteValues", "zipkin.autocompleteValues", "key", key)
        return sorted(strings_from_body(json_body(self._send(req, cancel))))

    def close(self) -> None:
        """Releases connections when this client owns them (no-op for ``client.query``).
        Idempotent."""
        if self._owned:
            self._transport.close()

    def __enter__(self) -> ZipkinQueryClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()


# --------------------------------------------------------------------------- async


class AsyncZipkinQueryClient(AsyncBaseTraceQueryClient):
    """Asynchronous :class:`ZipkinQueryClient` (same methods, awaited; cancel by cancelling the
    task). ``source`` may be an :class:`~grow_zipkin.AsyncZipkinClient`."""

    def __init__(
        self,
        source: AsyncZipkinClient | ZipkinConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        from .client import AsyncZipkinClient  # noqa: PLC0415 - import cycle

        self._now = clock
        if isinstance(source, AsyncZipkinClient):
            self.config = source.config
            self._transport = source.transport
            self._owned = False
        else:
            self.config = (source if source is not None else ZipkinConfig.from_env()).validate()
            self._transport = AsyncHttpTransport(
                self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
            )
            self._owned = True

    @classmethod
    def from_env(
        cls, env: Mapping[str, str] | None = None, **overrides: Any
    ) -> AsyncZipkinQueryClient:
        """A standalone async query client from the environment."""
        return cls(ZipkinConfig.from_env(env, **overrides))

    async def _send(self, req: Request, allow: Sequence[int] = ()) -> Response:
        return await asend(self._transport, self.config, req, allow=allow)

    async def get_trace_spans(self, trace_id: str) -> list[ZipkinSpan] | None:
        """See :meth:`ZipkinQueryClient.get_trace_spans`."""
        return _trace_spans(await self._send(_trace_request(trace_id), (404,)))

    async def get_trace(self, trace_id: str) -> Trace | None:
        """See :meth:`ZipkinQueryClient.get_trace`."""
        spans = await self.get_trace_spans(trace_id)
        return None if spans is None else zipkin_trace_to_trace(spans)

    async def get_traces(
        self, query: ZipkinTraceQuery | None = None, /, **filters: Any
    ) -> list[list[ZipkinSpan]]:
        """See :meth:`ZipkinQueryClient.get_traces`."""
        plan = _traces_plan(_merge_query(query, filters))
        return plan.result(await self._send(plan.request))

    async def get_trace_many(self, trace_ids: Iterable[str]) -> list[list[ZipkinSpan]]:
        """See :meth:`ZipkinQueryClient.get_trace_many`."""
        ids = _many_ids(trace_ids)
        if not ids:
            return []
        by_id: dict[str, list[ZipkinSpan]] = {}
        for i in range(0, len(ids), TRACE_MANY_CHUNK):
            chunk = ids[i : i + TRACE_MANY_CHUNK]
            if len(chunk) == 1:
                one = await self.get_trace_spans(chunk[0])
                traces = [] if one is None else [one]
            else:
                traces = zipkin_traces_from_body(json_body(await self._send(_many_request(chunk))))
            for t in traces:
                by_id[_padded(t[0].trace_id)] = t
        return _ordered(ids, by_id)

    async def search_traces(self, query: TraceSearchQuery | None = None) -> list[TraceSummary]:
        """See :meth:`ZipkinQueryClient.search_traces`."""
        return _summaries(await self.get_traces(_search_native(query, self._now)))

    async def list_services(self) -> list[str]:
        """See :meth:`ZipkinQueryClient.list_services`."""
        req = _names_request("/api/v2/services", "zipkin.listServices")
        return sorted(strings_from_body(json_body(await self._send(req))))

    async def list_spans(self, service_name: str) -> list[str]:
        """See :meth:`ZipkinQueryClient.list_spans`."""
        req = _names_request("/api/v2/spans", "zipkin.listSpans", "serviceName", service_name)
        return sorted(strings_from_body(json_body(await self._send(req))))

    async def list_operations(self, service: str) -> list[str]:
        """SPEC §5 alias of :meth:`list_spans`."""
        return await self.list_spans(service)

    async def list_remote_services(self, service_name: str) -> list[str]:
        """See :meth:`ZipkinQueryClient.list_remote_services`."""
        req = _names_request(
            "/api/v2/remoteServices", "zipkin.listRemoteServices", "serviceName", service_name
        )
        return sorted(strings_from_body(json_body(await self._send(req))))

    async def get_dependencies(  # type: ignore[override]
        self,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        end_ts: datetime | int | None = None,
        lookback_s: float | timedelta | None = None,
    ) -> list[ZipkinDependencyLink]:
        """See :meth:`ZipkinQueryClient.get_dependencies`."""
        req = _dependencies_request(start, end, end_ts, lookback_s, self._now)
        return dependencies_from_body(json_body(await self._send(req)))

    async def autocomplete_keys(self) -> list[str]:
        """See :meth:`ZipkinQueryClient.autocomplete_keys`."""
        req = _names_request("/api/v2/autocompleteKeys", "zipkin.autocompleteKeys")
        return strings_from_body(json_body(await self._send(req)))

    async def autocomplete_values(self, key: str) -> list[str]:
        """See :meth:`ZipkinQueryClient.autocomplete_values`."""
        req = _names_request("/api/v2/autocompleteValues", "zipkin.autocompleteValues", "key", key)
        return sorted(strings_from_body(json_body(await self._send(req))))

    async def aclose(self) -> None:
        """Releases connections when this client owns them. Idempotent."""
        if self._owned:
            await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncZipkinQueryClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()
