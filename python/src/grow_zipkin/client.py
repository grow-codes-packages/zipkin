"""Zipkin clients (sync and async).

This file is owned by the service package (generated once by sdk-kit). The skeleton talks to
``GET /health``, ``GET /v1/ping`` and ``GET /version``; replace the paths with the service API
and hang service modules off the client (``client.kv`` …).
"""

from __future__ import annotations

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
    NotFoundError,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
    ValidationError,
)
from .config import ZipkinConfig

__all__ = ["ZipkinClient", "AsyncZipkinClient"]

_HEALTH = Request("GET", "/health", operation="health", raise_for_status=False, max_retries=0)
_PING = "/v1/ping"
_VERSION = "/version"


def _auth_headers(config: ZipkinConfig) -> dict[str, str]:
    if config.token is None:
        return {}
    return {"Authorization": f"Bearer {config.token.reveal()}"}


def _health_from_response(resp: Response) -> HealthStatus:
    status = "ok" if resp.ok else "unhealthy"
    details: dict[str, str] = {}
    try:
        body: Any = resp.json()
    except ValidationError:
        body = None
    if isinstance(body, Mapping):
        for key, value in body.items():
            if key == "status" and isinstance(value, str) and value:
                status = value
            elif isinstance(value, (str, int, float, bool)):
                details[str(key)] = str(value)
    if not resp.ok:
        details.setdefault("httpStatus", str(resp.status))
    return HealthStatus(healthy=resp.ok, status=status, details=details)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    word = "unreachable" if isinstance(err, (UnavailableError, TimeoutError)) else "unhealthy"
    return HealthStatus(healthy=False, status=word, details={"error": err.message})


def _version_from_response(resp: Response) -> str:
    body = resp.json()
    version = body.get("version") if isinstance(body, Mapping) else None
    if not isinstance(version, str) or not version:
        raise ValidationError("server_version: response has no version")
    return version


def _unsupported_version(err: ServiceError) -> ServiceError:
    if isinstance(err, NotFoundError):
        return UnsupportedError(
            "server_version: not exposed by this deployment",
            http_status=err.http_status,
            request_id=err.request_id,
        )
    return err


class ZipkinClient:
    """Synchronous Zipkin client. Thread-safe; use as a context manager or call
    :meth:`close`.

    Every I/O method accepts ``cancel=`` (a :class:`CancelToken`) to abort from another thread.
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
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> ZipkinClient:
        """Build a client from the ``GROW_<KEY>_*`` and vendor environment variables."""
        return cls(ZipkinConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources (watchers, renewers) stopped by :meth:`close`."""
        return self._resources

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request through the client's transport (retries, hooks, redaction)."""
        return self._transport.request(req, cancel=cancel)

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Unauthenticated health check; never raises for an unhealthy/unreachable service."""
        try:
            resp = self._transport.request(_HEALTH, cancel=cancel)
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Authenticated round trip; returns the latency in milliseconds."""
        started = time.monotonic()
        self._transport.request(
            Request("GET", _PING, operation="ping", headers=_auth_headers(self.config)),
            cancel=cancel,
        )
        return (time.monotonic() - started) * 1000.0

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """The server version; :class:`UnsupportedError` when the service exposes none."""
        try:
            resp = self._transport.request(
                Request("GET", _VERSION, operation="server_version"), cancel=cancel
            )
        except ServiceError as err:
            raise _unsupported_version(err) from None
        return _version_from_response(resp)

    def close(self) -> None:
        """Stop watchers/renewers and close connections (idempotent)."""
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

    Cancel a call by cancelling its task: the SDK raises :class:`CancelledError`, which is also
    an :class:`asyncio.CancelledError`.
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
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> AsyncZipkinClient:
        return cls(ZipkinConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        return self._resources

    async def request(self, req: Request) -> Response:
        return await self._transport.request(req)

    async def health(self) -> HealthStatus:
        try:
            resp = await self._transport.request(_HEALTH)
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    async def ping(self) -> float:
        started = time.monotonic()
        await self._transport.request(
            Request("GET", _PING, operation="ping", headers=_auth_headers(self.config))
        )
        return (time.monotonic() - started) * 1000.0

    async def server_version(self) -> str:
        try:
            resp = await self._transport.request(
                Request("GET", _VERSION, operation="server_version")
            )
        except ServiceError as err:
            raise _unsupported_version(err) from None
        return _version_from_response(resp)

    async def aclose(self) -> None:
        """Stop watchers/renewers and close connections (idempotent)."""
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
