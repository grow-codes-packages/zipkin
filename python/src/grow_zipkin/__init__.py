"""Grow Codes SDK for Zipkin.

The Zipkin v2 query API (:class:`ZipkinQueryClient`), a span reporter for applications without
OpenTelemetry (:class:`ZipkinReporter`) and OpenTelemetry tracing that exports Zipkin v2 JSON
(:func:`init_tracing`, :class:`ZipkinSpanExporter`), with sync and async clients.

>>> from grow_zipkin import ZipkinClient, init_tracing
>>> client = ZipkinClient.from_env()  # doctest: +SKIP
>>> client.query.list_services()  # doctest: +SKIP

Zipkin is a trademark of The OpenZipkin authors. This package is not affiliated with or endorsed
by The OpenZipkin authors.
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import AsyncZipkinClient, ZipkinClient, ZipkinServerInfo
from .config import DEFAULT_URL, SPANS_PATH, ZipkinConfig, base_from_endpoint, spans_endpoint

__all__ = [
    "__version__",
    "ZipkinClient",
    "AsyncZipkinClient",
    "ZipkinServerInfo",
    "ZipkinConfig",
    "DEFAULT_URL",
    "SPANS_PATH",
    "base_from_endpoint",
    "spans_endpoint",
]
__all__ += _core.__all__

from . import _tracing  # noqa: E402
from ._tracing import *  # noqa: E402, F403

__all__ += _tracing.__all__

from . import exporter, model, query, reporter, setup  # noqa: E402
from .exporter import *  # noqa: E402, F403
from .model import *  # noqa: E402, F403
from .query import *  # noqa: E402, F403
from .reporter import *  # noqa: E402, F403
from .setup import *  # noqa: E402, F403

__all__ += model.__all__ + reporter.__all__ + query.__all__ + exporter.__all__ + setup.__all__
