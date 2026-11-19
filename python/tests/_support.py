"""Test support: a programmable local HTTP server and config helpers."""

from __future__ import annotations

import asyncio
import dataclasses
import json
import socket
import threading
import time
from collections import defaultdict, deque
from collections.abc import Awaitable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import grow_zipkin as sdk


@dataclasses.dataclass
class Reply:
    status: int = 200
    body: Any = None
    headers: dict[str, str] = dataclasses.field(default_factory=dict)
    delay: float = 0.0  # before the status line
    content_type: str | None = None  # overrides the default text/plain or application/json
    drip: float = 0.0  # pause between body chunks (slow body)


@dataclasses.dataclass
class Seen:
    method: str
    path: str
    headers: dict[str, str]
    body: bytes


class MockServer:
    """Threaded HTTP server; queue replies per path, inspect the requests it saw."""

    def __init__(self) -> None:
        self.replies: dict[str, deque[Reply]] = defaultdict(deque)
        self.defaults: dict[str, Reply] = {}
        self.seen: list[Seen] = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
                pass

            def _handle(self) -> None:
                length = int(self.headers.get("content-length") or 0)
                body = self.rfile.read(length) if length else b""
                path = self.path.split("?", 1)[0]
                server.seen.append(Seen(self.command, self.path, dict(self.headers), body))
                queue = server.replies.get(path)
                reply = queue.popleft() if queue else server.defaults.get(path, Reply())
                if reply.delay:
                    time.sleep(reply.delay)
                if isinstance(reply.body, (bytes, str)):
                    payload = reply.body.encode() if isinstance(reply.body, str) else reply.body
                    ctype = "text/plain"
                else:
                    payload = json.dumps(reply.body if reply.body is not None else {}).encode()
                    ctype = "application/json"
                try:
                    self.send_response(reply.status)
                    self.send_header("Content-Type", reply.content_type or ctype)
                    for k, v in reply.headers.items():
                        self.send_header(k, v)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    if reply.drip:
                        for i in range(len(payload)):
                            self.wfile.write(payload[i : i + 1])
                            self.wfile.flush()
                            time.sleep(reply.drip)
                    else:
                        self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = _handle

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        host, port = self.httpd.server_address[:2]
        return f"http://{host!s}:{port}"

    def enqueue(self, path: str, *replies: Reply) -> None:
        self.replies[path].extend(replies)

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def no_jitter() -> float:
    return 0.0


def fast_retry(**kw: Any) -> sdk.RetryPolicy:
    values: dict[str, Any] = {"initial_backoff": 0.01, "max_backoff": 0.05, "random": no_jitter}
    values.update(kw)
    return sdk.RetryPolicy(**values)


def make_config(url: str, **kw: Any) -> sdk.ZipkinConfig:
    """A config with fast retries. Zipkin has no token option: ``token=`` becomes a gateway
    ``Authorization: Bearer`` header (redacted like any credential)."""
    kw.setdefault("retry", fast_retry())
    token = kw.pop("token", None)
    if token is not None:
        kw["headers"] = {**kw.get("headers", {}), "Authorization": f"Bearer {token}"}
    return sdk.ZipkinConfig(url=url, **kw)


def closed_port_url() -> str:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return f"http://127.0.0.1:{port}"


def spawn_capturing(awaitable: Awaitable[Any], sink: list[BaseException]) -> asyncio.Task[Any]:
    """Run ``awaitable`` as a task and record the exception it raises *inside* the task.

    On Python < 3.11 awaiting a cancelled task raises a fresh ``asyncio.CancelledError``, so the
    SDK's ``CancelledError`` subclass is only observable from within the task itself.
    """

    async def run() -> Any:
        try:
            return await awaitable
        except BaseException as exc:
            sink.append(exc)
            raise

    return asyncio.ensure_future(run())
