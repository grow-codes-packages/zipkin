"""Configuration for the Zipkin client.

This file is owned by the service package (generated once by sdk-kit). Add service fields
here, list vendor environment variables in ``ENV_VENDOR`` and extend :meth:`validate`.
"""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping
from typing import Any, ClassVar, TypeVar

from ._core import BaseConfig, ConfigError, EnvReader, Secret

__all__ = ["DEFAULT_URL", "ZipkinConfig"]

#: Default endpoint when neither the config nor the environment sets one.
DEFAULT_URL = "http://127.0.0.1:9411"

_C = TypeVar("_C", bound="ZipkinConfig")


@dataclasses.dataclass(frozen=True, repr=False)
class ZipkinConfig(BaseConfig):
    """Immutable settings for :class:`~grow_zipkin.ZipkinClient`.

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`. Durations are
    float seconds; ``datetime.timedelta`` is accepted. ``token`` accepts a ``str`` and is stored
    as a :class:`Secret`; ``repr()`` never shows it.

    Environment (``from_env``), prefix ``GROW_ZIPKIN_``:
    ``URL``, ``TOKEN``, ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``,
    ``MAX_RETRIES``, ``CA_CERT``, ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``,
    ``PROXY``.
    """

    #: Vendor variables per GROW_* suffix, in precedence order (e.g. ``"URL": ("BAO_ADDR",)``).
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {}

    url: str = DEFAULT_URL
    token: Secret | None = None

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        token = reader.string("TOKEN", cls.ENV_VENDOR.get("TOKEN", ()))
        if token is not None:
            values["token"] = Secret(token)
        return values

    def validate(self: _C) -> _C:
        """Common checks plus service rules; raises :class:`ConfigError`."""
        super().validate()
        if self.token is not None and not self.token.reveal().strip():
            raise ConfigError("config: token must not be blank")
        return self
