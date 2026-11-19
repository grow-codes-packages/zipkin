"""Zipkin clients (sync and async) — service-owned.

Health, ping and server version of a Zipkin server plus the query client, the span reporter and
the tracing preset.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import threading
import time
from collections.abc import Mapping
from types import TracebackType
from typing import Any

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
)
from ._http import asend, json_body, send
from ._tracing import TracingConfig
from .config import ZipkinConfig
from .query import AsyncZipkinQueryClient, ZipkinQueryClient
from .reporter import AsyncZipkinReporter, ZipkinReporter
from .setup import (
    ZipkinTracingConfig,
    ZipkinTracingHandle,
    _explicit,
    init_tracing,
    tracing_config_from_env,
)

__all__ = ["AsyncZipkinClient", "ZipkinClient", "ZipkinServerInfo"]

_HEALTH = Request("GET", "/health", operation="health", max_retries=0)
_PING = Request("GET", "/api/v2/services", operation="ping")
_INFO = Request("GET", "/info", operation="serverVersion")


@dataclasses.dataclass(frozen=True)
class ZipkinServerInfo:
    """Build information of the server (``GET /info``)."""

    #: e.g. ``3.6.1``.
    version: str | None = None
    #: Short git commit.
    commit: str | None = None


def _health_from_response(resp: Response) -> HealthStatus:
    if resp.status == 503:
        return HealthStatus(False, "DOWN", {"httpStatus": "503"})
    try:
        body = json_body(resp)
    except ServiceError:
        body = None
    body = body if isinstance(body, Mapping) else {}
    status = body.get("status") if isinstance(body.get("status"), str) else "UP"
    details: dict[str, str] = {}
    zipkin = body.get("zipkin")
    if isinstance(zipkin, Mapping):
        if isinstance(zipkin.get("status"), str):
            details["zipkin"] = zipkin["status"]
        if zipkin.get("details") is not None:
            details["storage"] = json.dumps(zipkin["details"], separators=(",", ":"))
    return HealthStatus(str(status).upper() == "UP", str(status), details)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    if isinstance(err, (UnavailableError, TimeoutError)) and err.http_status is None:
        return HealthStatus(False, "unreachable", {"error": err.code.value})
    details = {"error": err.code.value}
    if err.http_status is not None:
        details["httpStatus"] = str(err.http_status)
    return HealthStatus(False, "unhealthy", details)


def _info_from_response(resp: Response) -> ZipkinServerInfo:
    if resp.status == 404:
        return ZipkinServerInfo()
    body = json_body(resp)
    zipkin = body.get("zipkin") if isinstance(body, Mapping) else None
    if not isinstance(zipkin, Mapping):
        return ZipkinServerInfo()
    version, commit = zipkin.get("version"), zipkin.get("commit")
    return ZipkinServerInfo(
        version=version if isinstance(version, str) and version else None,
        commit=commit if isinstance(commit, str) and commit else None,
    )


def _version(info: ZipkinServerInfo) -> str:
    if info.version is None:
        raise UnsupportedError("serverVersion: the service does not report a version")
    return info.version


def _tracing_kwargs(
    client_config: ZipkinConfig,
    config: TracingConfig | None,
    options: dict[str, Any],
) -> dict[str, Any]:
    """``init_tracing`` keywords for a client: with the native protocol the exporter sends to
    the client's endpoint unless the tracing config sets one, with the client's connection
    settings."""
    fields = {f.name for f in dataclasses.fields(ZipkinTracingConfig)}
    explicit = _explicit(config, {k: v for k, v in options.items() if k in fields})
    env = options.get("env")
    protocol = explicit.get("protocol")
    if protocol is None and env is not False:
        protocol = tracing_config_from_env(None, env=env).protocol
    if (protocol or "native") == "native" and "endpoint" not in explicit:
        options["endpoint"] = client_config.endpoint
    options.setdefault("client_config", client_config)
    return options


class ZipkinClient:
    """Synchronous client for Zipkin (port 9411). Thread-safe; use as a context manager or call
    :meth:`close`.

    Health, ping and server version, plus the query API (:attr:`query`), the direct span
    reporter (:attr:`reporter`) and tracing setup (:meth:`init_tracing`). :meth:`close` also
    shuts down every tracing handle created through this client. Every I/O method accepts
    ``cancel=`` (a :class:`~grow_zipkin.CancelToken`) to abort from another thread.

    Args:
        config: Settings; default :meth:`ZipkinConfig.from_env`.
        http_transport: Test seam (an ``httpx`` transport).
    """

    def __init__(
        self,
        config: ZipkinConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if config is None:
            config = ZipkinConfig.from_env()
        self.config = config.validate()
        self._http_transport = http_transport
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._lock = threading.Lock()
        self._query: ZipkinQueryClient | None = None
        self._reporter: ZipkinReporter | None = None
        self._reporters: list[ZipkinReporter] = []
        self._handles: list[ZipkinTracingHandle] = []
        self._closed = False

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> ZipkinClient:
        """Build a client from the ``GROW_ZIPKIN_*`` and vendor environment variables."""
        return cls(ZipkinConfig.from_env(env, **overrides))

    @property
    def transport(self) -> HttpTransport:
        """The shared HTTP transport (for service modules)."""
        return self._transport

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`close`."""
        return self._resources

    @property
    def query(self) -> ZipkinQueryClient:
        """The trace query client (shares this client's connections)."""
        with self._lock:
            if self._query is None:
                self._query = ZipkinQueryClient(self)
            return self._query

    @property
    def reporter(self) -> ZipkinReporter:
        """The span reporter (shares this client's connections; no compression, 512 spans per
        POST)."""
        with self._lock:
            if self._reporter is None:
                self._reporter = self._new_reporter({})
            return self._reporter

    def create_reporter(self, **options: Any) -> ZipkinReporter:
        """A reporter with other options (``compression``, ``max_batch_size``, ``endpoint``) that
        shares this client's connections; closed by :meth:`close`."""
        with self._lock:
            return self._new_reporter(options)

    def _new_reporter(self, options: dict[str, Any]) -> ZipkinReporter:
        options.setdefault("http_transport", self._http_transport)
        reporter = ZipkinReporter(self, **options)
        self._reporters.append(reporter)
        return reporter

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request through the client's transport (retries, hooks, redaction)."""
        return send(self._transport, self.config, req, cancel=cancel)

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Liveness: ``GET /health`` → ``{"status":"UP"}``.

        A 503 (``DOWN``, storage unavailable) gives ``healthy=False`` without retries. Never
        raises for an unhealthy or unreachable service (only ``CANCELLED`` and ``CONFIG``).
        """
        try:
            resp = send(self._transport, self.config, _HEALTH, cancel=cancel, allow=(503,))
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Round trip through the query API and storage (``GET /api/v2/services``; through a
        gateway this also proves the auth headers). Returns the latency in milliseconds."""
        started = time.monotonic()
        send(self._transport, self.config, _PING, cancel=cancel)
        return (time.monotonic() - started) * 1000.0

    def server_info(self, *, cancel: CancelToken | None = None) -> ZipkinServerInfo:
        """Build information: ``GET /info`` → ``{"zipkin":{"version","commit"}}``."""
        return _info_from_response(
            send(self._transport, self.config, _INFO, cancel=cancel, allow=(404,))
        )

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """Server version (e.g. ``3.6.1``) from ``GET /info``; :class:`UnsupportedError` when
        the server (or a gateway) does not report one."""
        return _version(self.server_info(cancel=cancel))

    def init_tracing(
        self, config: TracingConfig | None = None, **options: Any
    ) -> ZipkinTracingHandle:
        """Starts OpenTelemetry tracing to this Zipkin (see :func:`~grow_zipkin.init_tracing`).

        The native exporter uses this client's endpoint, headers, TLS, proxy, retries, hooks and
        logger; an explicit tracing ``endpoint`` wins. The handle is shut down by :meth:`close`.
        ``options`` are the keywords of :func:`~grow_zipkin.init_tracing`.
        """
        with self._lock:
            if self._closed:
                raise ConfigError("init_tracing: client is closed")
        handle = init_tracing(config, **_tracing_kwargs(self.config, config, dict(options)))
        with self._lock:
            self._handles.append(handle)
        return handle

    def close(self) -> None:
        """Shut down tracing handles created through this client (flushing their batch
        processors), close reporters and connections. Idempotent."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            handles, self._handles = self._handles, []
            reporters, self._reporters = self._reporters, []
        for handle in handles:
            with contextlib.suppress(Exception):  # close never fails on a handle
                handle.shutdown()
        for reporter in reporters:
            reporter.close()
        self._resources.stop_all()
        self._transport.close()

    def __enter__(self) -> ZipkinClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"ZipkinClient(config={self.config!r})"


class AsyncZipkinClient:
    """Asynchronous Zipkin client; use ``async with`` or call :meth:`aclose`.

    Same surface as :class:`ZipkinClient` (:attr:`query` and :attr:`reporter` are the async
    variants). Cancel a call by cancelling its task: the SDK raises
    :class:`~grow_zipkin.CancelledError`, which is also an :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: ZipkinConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if config is None:
            config = ZipkinConfig.from_env()
        self.config = config.validate()
        self._http_transport = http_transport
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._query: AsyncZipkinQueryClient | None = None
        self._reporter: AsyncZipkinReporter | None = None
        self._reporters: list[AsyncZipkinReporter] = []
        self._handles: list[ZipkinTracingHandle] = []
        self._closed = False

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> AsyncZipkinClient:
        """Build an async client from the environment."""
        return cls(ZipkinConfig.from_env(env, **overrides))

    @property
    def transport(self) -> AsyncHttpTransport:
        """The shared async HTTP transport."""
        return self._transport

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    @property
    def query(self) -> AsyncZipkinQueryClient:
        """The async trace query client (shares this client's connections)."""
        if self._query is None:
            self._query = AsyncZipkinQueryClient(self)
        return self._query

    @property
    def reporter(self) -> AsyncZipkinReporter:
        """The async span reporter (shares this client's connections)."""
        if self._reporter is None:
            self._reporter = self.create_reporter()
        return self._reporter

    def create_reporter(self, **options: Any) -> AsyncZipkinReporter:
        """An async reporter with other options; closed by :meth:`aclose`."""
        options.setdefault("http_transport", self._http_transport)
        reporter = AsyncZipkinReporter(self, **options)
        self._reporters.append(reporter)
        return reporter

    async def request(self, req: Request) -> Response:
        """Send a raw request through the client's transport."""
        return await asend(self._transport, self.config, req)

    async def health(self) -> HealthStatus:
        """See :meth:`ZipkinClient.health`."""
        try:
            resp = await asend(self._transport, self.config, _HEALTH, allow=(503,))
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    async def ping(self) -> float:
        """See :meth:`ZipkinClient.ping`."""
        started = time.monotonic()
        await asend(self._transport, self.config, _PING)
        return (time.monotonic() - started) * 1000.0

    async def server_info(self) -> ZipkinServerInfo:
        """See :meth:`ZipkinClient.server_info`."""
        return _info_from_response(await asend(self._transport, self.config, _INFO, allow=(404,)))

    async def server_version(self) -> str:
        """See :meth:`ZipkinClient.server_version`."""
        return _version(await self.server_info())

    def init_tracing(
        self, config: TracingConfig | None = None, **options: Any
    ) -> ZipkinTracingHandle:
        """See :meth:`ZipkinClient.init_tracing` (OpenTelemetry export runs on its own worker
        thread; the handle is shut down by :meth:`aclose`)."""
        if self._closed:
            raise ConfigError("init_tracing: client is closed")
        handle = init_tracing(config, **_tracing_kwargs(self.config, config, dict(options)))
        self._handles.append(handle)
        return handle

    async def aclose(self) -> None:
        """Shut down tracing handles, close reporters and connections. Idempotent."""
        if self._closed:
            return
        self._closed = True
        handles, self._handles = self._handles, []
        reporters, self._reporters = self._reporters, []
        for handle in handles:
            with contextlib.suppress(Exception):  # close never fails on a handle
                await asyncio.to_thread(handle.shutdown)
        for reporter in reporters:
            await reporter.aclose()
        self._resources.stop_all()
        await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncZipkinClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncZipkinClient(config={self.config!r})"
