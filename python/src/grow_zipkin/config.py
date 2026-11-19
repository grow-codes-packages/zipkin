"""Configuration for the Zipkin client (service-owned).

Connection settings of the Zipkin query and ingest APIs (port 9411). Stock Zipkin has no
authentication: put gateway credentials in ``headers`` (they are redacted everywhere).
"""

from __future__ import annotations

import dataclasses
import os
from collections.abc import Mapping
from typing import Any, ClassVar, TypeVar
from urllib.parse import urlsplit

from ._core import BaseConfig, ConfigError, EnvReader
from ._core.redaction import is_sensitive_header

__all__ = [
    "DEFAULT_URL",
    "SPANS_PATH",
    "ZipkinConfig",
    "base_from_endpoint",
    "spans_endpoint",
]

#: Default server URL when neither the config nor the environment sets one.
DEFAULT_URL = "http://127.0.0.1:9411"
#: Path of the Zipkin v2 span ingest API.
SPANS_PATH = "/api/v2/spans"

_C = TypeVar("_C", bound="ZipkinConfig")


def spans_endpoint(base: str) -> str:
    """``<base>/api/v2/spans``, appended once.

    A base that already ends with the spans path is kept, a gateway prefix is preserved and
    trailing slashes are dropped (``https://gw.example/zipkin/`` →
    ``https://gw.example/zipkin/api/v2/spans``).
    """
    trimmed = base.rstrip("/")
    return trimmed if trimmed.endswith(SPANS_PATH) else trimmed + SPANS_PATH


def base_from_endpoint(endpoint: str) -> str:
    """The server base URL of a spans endpoint: strips one trailing ``/api/v2/spans``."""
    trimmed = endpoint.rstrip("/")
    return trimmed[: -len(SPANS_PATH)] if trimmed.endswith(SPANS_PATH) else trimmed


def _is_http_url(value: str) -> bool:
    try:
        parts = urlsplit(value)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.hostname)


@dataclasses.dataclass(frozen=True, repr=False)
class ZipkinConfig(BaseConfig):
    """Immutable settings for :class:`~grow_zipkin.ZipkinClient`.

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`. Durations are
    float seconds; ``datetime.timedelta`` is accepted.

    Attributes:
        url: Base URL of the Zipkin server. A gateway path prefix is kept
            (``https://gw.example/zipkin`` → ``https://gw.example/zipkin/api/v2/...``).
            Default ``http://127.0.0.1:9411``.
        endpoint: Full span ingest URL used by :class:`~grow_zipkin.ZipkinReporter` and the
            tracing exporter. Default ``<url>/api/v2/spans``.

    Environment (:meth:`from_env`, explicit > ``GROW_ZIPKIN_*`` > vendor > default):

    - ``url``: ``GROW_ZIPKIN_URL`` > ``ZIPKIN_URL`` > ``OTEL_EXPORTER_ZIPKIN_ENDPOINT`` without
      its trailing ``/api/v2/spans`` > ``http://127.0.0.1:9411``.
    - ``endpoint``: ``GROW_ZIPKIN_ENDPOINT`` > ``OTEL_EXPORTER_ZIPKIN_ENDPOINT`` (verbatim) >
      ``<url>/api/v2/spans``.
    - ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``, ``CA_CERT``,
      ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``, ``PROXY`` with the
      ``GROW_ZIPKIN_`` prefix (SPEC §2.2).
    """

    #: Vendor variables per GROW_* suffix, in precedence order.
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {
        "URL": ("ZIPKIN_URL",),
        "ENDPOINT": ("OTEL_EXPORTER_ZIPKIN_ENDPOINT",),
    }

    url: str = DEFAULT_URL
    endpoint: str = ""

    def __post_init__(self) -> None:
        super().__post_init__()
        if not self.endpoint:
            object.__setattr__(self, "endpoint", spans_endpoint(self.url))

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        endpoint = reader.string("ENDPOINT", cls.ENV_VENDOR.get("ENDPOINT", ()))
        if endpoint is not None:
            values["endpoint"] = endpoint
        return values

    @classmethod
    def from_env(cls: type[_C], env: Mapping[str, str] | None = None, **overrides: Any) -> _C:
        """Build from the environment, then validate.

        Precedence: explicit keyword > ``GROW_ZIPKIN_<NAME>`` > vendor variables > default;
        without any URL variable the base of ``OTEL_EXPORTER_ZIPKIN_ENDPOINT`` is the ``url``.
        ``env`` defaults to ``os.environ``; pass a dict in tests.
        """
        source: Mapping[str, str] = os.environ if env is None else env
        values = cls._env_values(EnvReader(source))
        otel = source.get("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "")
        if "url" not in values and otel.strip():
            values["url"] = base_from_endpoint(otel.strip())
        values.update({k: v for k, v in overrides.items() if v is not None})
        return cls(**values).validate()

    def validate(self: _C) -> _C:
        """Common checks plus ``endpoint`` (http/https URL); raises :class:`ConfigError`."""
        super().validate()
        if not _is_http_url(self.endpoint):
            raise ConfigError("config: endpoint must be an absolute http or https URL")
        return self

    def known_secrets(self) -> list[str]:
        """Values of sensitive headers (``Authorization``, ``X-Api-Key`` …) to redact from error
        messages and logs; for ``<scheme> <credential>`` values also the bare credential."""
        out: list[str] = []
        for name, value in self.headers.items():
            if not value or not is_sensitive_header(name):
                continue
            out.append(value)
            scheme, _, credential = value.partition(" ")
            if credential.strip() and scheme.isalpha():
                out.append(credential.strip())  # "Bearer <token>" → also the bare token
        return out
