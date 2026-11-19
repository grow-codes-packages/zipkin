"""Request helpers shared by the Zipkin client, query client and reporter (service-owned).

Zipkin answers errors with plain text under ``content-type: text/*``, which the generic error
mapping does not read; these helpers map error responses themselves so the server's message
(redacted, truncated) ends up in the SDK error.
"""

from __future__ import annotations

import dataclasses
from collections.abc import Collection
from typing import Any
from urllib.parse import urlsplit

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    HttpTransport,
    Request,
    Response,
    error_from_status,
    parse_retry_after,
)
from .config import ZipkinConfig

__all__: list[str] = []


def check(
    config: ZipkinConfig, req: Request, resp: Response, allow: Collection[int] = ()
) -> Response:
    """Return ``resp`` when it is a success or an allowed status; raise the mapped error."""
    if resp.status < 400 or resp.status in allow:
        return resp
    headers = httpx.Headers(resp.headers)
    content_type = headers.get("content-type", "")
    if content_type.startswith("text/") and not content_type.startswith("text/plain"):
        headers["content-type"] = "text/plain; charset=utf-8"
    retry_after = None
    if resp.status in (429, 503):
        retry_after = parse_retry_after(headers.get("retry-after"))
    raise error_from_status(
        resp.status,
        req.op,
        content=resp.content,
        headers=headers,
        known_secrets=config.known_secrets(),
        retry_after=retry_after,
    )


def _unchecked(req: Request) -> Request:
    return dataclasses.replace(req, raise_for_status=False)


def send(
    transport: HttpTransport,
    config: ZipkinConfig,
    req: Request,
    *,
    cancel: CancelToken | None = None,
    allow: Collection[int] = (),
) -> Response:
    """Send with the transport's retries; map errors with :func:`check`."""
    return check(config, req, transport.request(_unchecked(req), cancel=cancel), allow)


async def asend(
    transport: AsyncHttpTransport,
    config: ZipkinConfig,
    req: Request,
    *,
    allow: Collection[int] = (),
) -> Response:
    """Async :func:`send`."""
    return check(config, req, await transport.request(_unchecked(req)), allow)


def json_body(resp: Response) -> Any:
    """Decoded JSON body; ``None`` for an empty body."""
    return resp.json() if resp.content.strip() else None


def split_endpoint(base_url: str, endpoint: str) -> tuple[str, str] | None:
    """``(origin, path)`` of ``endpoint`` when it is not under ``base_url``; ``None`` when it is
    (the client's transport can send to it)."""
    base = base_url.rstrip("/")
    if endpoint.startswith(base + "/"):
        return None
    parts = urlsplit(endpoint)
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    return f"{parts.scheme}://{parts.netloc}", path


def relative_path(base_url: str, endpoint: str) -> str:
    """The path of ``endpoint`` relative to ``base_url`` (``endpoint`` must be under it)."""
    return endpoint[len(base_url.rstrip("/")) :]
