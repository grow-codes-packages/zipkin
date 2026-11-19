"""ZipkinReporter — sends Zipkin v2 JSON spans to ``POST /api/v2/spans`` (service-owned)."""

from __future__ import annotations

import dataclasses
import gzip
import json
from collections.abc import Iterable, Mapping
from types import TracebackType
from typing import TYPE_CHECKING, Any, Literal

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    ConfigError,
    HttpTransport,
    Request,
    redact_url,
)
from ._http import asend, relative_path, send, split_endpoint
from .config import ZipkinConfig
from .model import ZipkinSpan, ZipkinSpanInput, normalize_zipkin_span

if TYPE_CHECKING:
    from .client import AsyncZipkinClient, ZipkinClient

__all__ = [
    "DEFAULT_MAX_BATCH_SIZE",
    "AsyncZipkinReporter",
    "ReporterCompression",
    "ZipkinReporter",
    "encode_spans",
]

#: Default number of spans per POST (SPEC batch default).
DEFAULT_MAX_BATCH_SIZE = 512

ReporterCompression = Literal["gzip", "none"]


def encode_spans(
    spans: Iterable[ZipkinSpanInput], operation: str = "zipkin.report"
) -> list[ZipkinSpan]:
    """Normalizes spans for ingest (see :func:`~grow_zipkin.normalize_zipkin_span`)."""
    return [normalize_zipkin_span(s, operation) for s in spans]


class _ReporterBase:
    endpoint: str
    compression: ReporterCompression
    max_batch_size: int

    def _setup(
        self,
        config: ZipkinConfig,
        compression: str,
        max_batch_size: int,
        endpoint: str | None,
    ) -> None:
        if compression not in ("gzip", "none"):
            raise ConfigError("zipkin.reporter: compression must be gzip or none")
        if (
            isinstance(max_batch_size, bool)
            or not isinstance(max_batch_size, int)
            or max_batch_size <= 0
        ):
            raise ConfigError("zipkin.reporter: max_batch_size must be a positive integer")
        target = endpoint or config.endpoint
        if not target.lower().startswith(("http://", "https://")):
            raise ConfigError("zipkin.reporter: endpoint must be an http or https URL")
        self.endpoint = target
        self.compression = compression  # type: ignore[assignment]
        self.max_batch_size = max_batch_size
        self._config = config

    def _requests(self, spans: Iterable[ZipkinSpanInput], path: str) -> list[Request]:
        normalized = encode_spans(spans)
        out = []
        for i in range(0, len(normalized), self.max_batch_size):
            batch = [s.to_json() for s in normalized[i : i + self.max_batch_size]]
            body = json.dumps(batch, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            if self.compression == "gzip":
                body = gzip.compress(body)
                headers["Content-Encoding"] = "gzip"
            out.append(
                Request(
                    "POST",
                    path,
                    operation="zipkin.report",
                    content=body,
                    headers=headers,
                    idempotent=True,
                )
            )
        return out


def _target(config: ZipkinConfig, endpoint: str) -> tuple[ZipkinConfig | None, str]:
    """``(own_config, path)``: ``own_config`` is set when the endpoint needs its own transport."""
    split = split_endpoint(config.url, endpoint)
    if split is None:
        return None, relative_path(config.url, endpoint)
    origin, path = split
    return dataclasses.replace(config, url=origin, endpoint=endpoint), path


class ZipkinReporter(_ReporterBase):
    """Reports spans to Zipkin without OpenTelemetry (synchronous).

    ``POST /api/v2/spans`` with a JSON array of v2 spans (``202 Accepted``). Spans are
    normalized locally first (:func:`~grow_zipkin.normalize_zipkin_span`), so an invalid span
    raises :class:`~grow_zipkin.ValidationError` before anything is sent. Ingest is idempotent
    (Zipkin merges spans by id), so 5xx, 429 and network errors are retried; a 400 or 413 is
    permanent.

    Get one from :attr:`ZipkinClient.reporter <grow_zipkin.ZipkinClient.reporter>` (shares the
    client's connections) or construct it from a config (owns its connections; call
    :meth:`close`).

    Args:
        source: A :class:`~grow_zipkin.ZipkinClient` (shared transport), a
            :class:`~grow_zipkin.ZipkinConfig` or ``None`` (``ZipkinConfig.from_env()``).
        compression: ``"gzip"`` compresses each request body. Default ``"none"``.
        max_batch_size: Spans per POST; larger reports are split. Default 512.
        endpoint: Ingest URL; default the config's ``endpoint``.
        http_transport: Test seam (an ``httpx`` transport) for an owned transport.
    """

    def __init__(
        self,
        source: ZipkinClient | ZipkinConfig | None = None,
        *,
        compression: ReporterCompression = "none",
        max_batch_size: int = DEFAULT_MAX_BATCH_SIZE,
        endpoint: str | None = None,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        from .client import ZipkinClient  # noqa: PLC0415 - import cycle

        shared: HttpTransport | None = None
        if isinstance(source, ZipkinClient):
            config, shared = source.config, source.transport
        else:
            config = (source if source is not None else ZipkinConfig.from_env()).validate()
        self._setup(config, compression, max_batch_size, endpoint)
        own, self._path = _target(config, self.endpoint)
        self._owned: HttpTransport | None = None
        if own is not None or shared is None:
            transport_config = own or config
            self._owned = HttpTransport(
                transport_config,
                known_secrets=transport_config.known_secrets,
                http_transport=http_transport,
            )
            self._send_config = transport_config
            self._transport = self._owned
        else:
            self._send_config = config
            self._transport = shared

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **options: Any) -> ZipkinReporter:
        """A standalone reporter from ``GROW_ZIPKIN_*`` / ``ZIPKIN_URL`` /
        ``OTEL_EXPORTER_ZIPKIN_ENDPOINT``; ``options`` are the constructor keywords."""
        return cls(ZipkinConfig.from_env(env), **options)

    def report(
        self, spans: Iterable[ZipkinSpanInput], *, cancel: CancelToken | None = None
    ) -> None:
        """Sends spans.

        An empty list sends nothing; more than ``max_batch_size`` spans are sent in several
        POSTs, in order. Spans are queryable once the call returns (immediately with in-memory
        storage; persistent backends may lag — use ``query.wait_for_trace``).
        """
        for req in self._requests(spans, self._path):
            send(self._transport, self._send_config, req, cancel=cancel)

    def close(self) -> None:
        """Releases connections when this reporter owns them (no-op for ``client.reporter``
        with the client's endpoint). Idempotent."""
        if self._owned is not None:
            self._owned.close()

    def __enter__(self) -> ZipkinReporter:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return (
            f"ZipkinReporter(endpoint={redact_url(self.endpoint)!r}, "
            f"compression={self.compression!r}, max_batch_size={self.max_batch_size})"
        )


class AsyncZipkinReporter(_ReporterBase):
    """Asynchronous :class:`ZipkinReporter` (same arguments; ``source`` may be an
    :class:`~grow_zipkin.AsyncZipkinClient`). Call :meth:`aclose` when it owns its
    connections."""

    def __init__(
        self,
        source: AsyncZipkinClient | ZipkinConfig | None = None,
        *,
        compression: ReporterCompression = "none",
        max_batch_size: int = DEFAULT_MAX_BATCH_SIZE,
        endpoint: str | None = None,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        from .client import AsyncZipkinClient  # noqa: PLC0415 - import cycle

        shared: AsyncHttpTransport | None = None
        if isinstance(source, AsyncZipkinClient):
            config, shared = source.config, source.transport
        else:
            config = (source if source is not None else ZipkinConfig.from_env()).validate()
        self._setup(config, compression, max_batch_size, endpoint)
        own, self._path = _target(config, self.endpoint)
        self._owned: AsyncHttpTransport | None = None
        if own is not None or shared is None:
            transport_config = own or config
            self._owned = AsyncHttpTransport(
                transport_config,
                known_secrets=transport_config.known_secrets,
                http_transport=http_transport,
            )
            self._send_config = transport_config
            self._transport = self._owned
        else:
            self._send_config = config
            self._transport = shared

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **options: Any) -> AsyncZipkinReporter:
        """A standalone async reporter from the environment (see
        :meth:`ZipkinReporter.from_env`)."""
        return cls(ZipkinConfig.from_env(env), **options)

    async def report(self, spans: Iterable[ZipkinSpanInput]) -> None:
        """Sends spans (see :meth:`ZipkinReporter.report`); cancel by cancelling the task."""
        for req in self._requests(spans, self._path):
            await asend(self._transport, self._send_config, req)

    async def aclose(self) -> None:
        """Releases connections when this reporter owns them. Idempotent."""
        if self._owned is not None:
            await self._owned.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncZipkinReporter:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return (
            f"AsyncZipkinReporter(endpoint={redact_url(self.endpoint)!r}, "
            f"compression={self.compression!r}, max_batch_size={self.max_batch_size})"
        )
