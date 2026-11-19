"""The Zipkin tracing preset (service-owned).

Native Zipkin v2 JSON export through :class:`~grow_zipkin.ZipkinSpanExporter` (default) or OTLP
to an OpenTelemetry Collector, W3C + B3 propagation and the environment precedence.
"""

from __future__ import annotations

import atexit
import contextlib
import dataclasses
import os
import tempfile
from collections.abc import Callable, Mapping, Sequence
from typing import Any, Literal
from urllib.parse import unquote, urlsplit

import httpx
from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.sdk.trace.id_generator import IdGenerator

from ._core import ConfigError, TlsConfig, parse_duration
from ._tracing import (
    SAMPLERS,
    ExporterFactory,
    PropagatorFactory,
    SpanBatchOptions,
    TraceProtocol,
    TracingConfig,
    TracingHandle,
    init_tracing_with,
)
from .config import SPANS_PATH, ZipkinConfig, spans_endpoint
from .exporter import ZipkinSpanExporter

__all__ = [
    "ZIPKIN_DEFAULT_PROPAGATORS",
    "ZIPKIN_DEFAULT_PROTOCOL",
    "ZipkinTracingConfig",
    "ZipkinTracingHandle",
    "create_zipkin_exporter_factory",
    "init_tracing",
    "order_propagators",
    "resolve_otlp_endpoint",
    "tracing_config_from_env",
]

#: Default protocol of the Zipkin preset: Zipkin v2 JSON to ``/api/v2/spans``.
ZIPKIN_DEFAULT_PROTOCOL: TraceProtocol = "native"

#: Default propagators of the Zipkin preset: W3C trace context + baggage and B3 single-header
#: (``b3``). Add ``b3multi`` to also inject the ``X-B3-*`` headers. Every B3 propagator extracts
#: both B3 forms.
ZIPKIN_DEFAULT_PROPAGATORS: tuple[str, ...] = ("tracecontext", "baggage", "b3")

_B3 = ("b3", "b3multi")
_PROTOCOLS = ("grpc", "http/protobuf", "native")


def order_propagators(names: Sequence[str]) -> list[str]:
    """The order in which propagators are composed.

    B3 (``b3``, ``b3multi``) first, every other propagator after it in the given order. On
    extract, later propagators overwrite earlier ones, so W3C ``traceparent`` wins when it and
    B3 disagree. Names are trimmed and lower-cased; duplicates are removed.
    """
    normalized = list(dict.fromkeys(n.strip().lower() for n in names))
    return [n for n in normalized if n in _B3] + [n for n in normalized if n not in _B3]


@dataclasses.dataclass(frozen=True, repr=False)
class ZipkinTracingConfig(TracingConfig):
    """:class:`~grow_zipkin.TracingConfig` with the Zipkin defaults: ``protocol="native"``,
    ``propagators=("tracecontext", "baggage", "b3")`` and ``service_name`` optional (it may come
    from ``OTEL_SERVICE_NAME``)."""

    service_name: str = ""
    protocol: TraceProtocol = ZIPKIN_DEFAULT_PROTOCOL
    propagators: Sequence[str] = ZIPKIN_DEFAULT_PROPAGATORS


class ZipkinTracingHandle(TracingHandle):
    """A running Zipkin tracing setup: :class:`~grow_zipkin.TracingHandle` plus the exporter
    ``endpoint`` in use."""

    endpoint: str = ""


def resolve_otlp_endpoint(protocol: str, endpoint: str) -> str:
    """The OTLP endpoint for a protocol: for ``http/protobuf`` an endpoint without a path gets
    ``/v1/traces``; any other endpoint is used as-is."""
    if protocol == "http/protobuf":
        path = urlsplit(endpoint).path
        if path in ("", "/"):
            return endpoint.rstrip("/") + "/v1/traces"
    return endpoint


def _hit(env: Mapping[str, str], *names: str) -> tuple[str, str] | None:
    for name in names:
        value = env.get(name)
        if value is not None and value.strip():
            return name, value.strip()
    return None


def _key_values(raw: str, name: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in raw.split(","):
        if not part.strip():
            continue
        key, eq, value = part.partition("=")
        if not eq or not key.strip():
            raise ConfigError(
                f"invalid value in environment variable {name}: expected key=value pairs"
            )
        out[unquote(key.strip(), errors="strict")] = unquote(value.strip(), errors="strict")
    return out


def _class_defaults(cls: type[TracingConfig]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for f in dataclasses.fields(cls):
        if f.default is not dataclasses.MISSING:
            out[f.name] = f.default
        elif f.default_factory is not dataclasses.MISSING:
            out[f.name] = f.default_factory()
    return out


def _explicit(config: TracingConfig | None, overrides: Mapping[str, Any]) -> dict[str, Any]:
    """Fields of ``config`` that differ from its class defaults, plus ``overrides``."""
    out: dict[str, Any] = {}
    if config is not None:
        defaults = _class_defaults(type(config))
        for f in dataclasses.fields(config):
            value = getattr(config, f.name)
            if f.name == "propagators":
                value = tuple(value)
                default = tuple(defaults.get(f.name, ()))
            else:
                default = defaults.get(f.name, dataclasses.MISSING)
            if value is not None and value != default:
                out[f.name] = value
    out.update({k: v for k, v in overrides.items() if v is not None})
    if isinstance(out.get("service_name"), str) and not out["service_name"].strip():
        del out["service_name"]
    return out


def _env_values(env: Mapping[str, str], protocol_hint: str | None) -> dict[str, Any]:
    values: dict[str, Any] = {}
    found = _hit(env, "GROW_ZIPKIN_PROTOCOL")
    if found is not None:
        name, raw = found
        if raw.lower() not in _PROTOCOLS:
            raise ConfigError(
                f"invalid value in environment variable {name}: "
                "expected native, grpc or http/protobuf"
            )
        values["protocol"] = raw.lower()
    protocol = protocol_hint or values.get("protocol") or ZIPKIN_DEFAULT_PROTOCOL
    if protocol == "native":
        endpoint = _hit(env, "GROW_ZIPKIN_ENDPOINT", "OTEL_EXPORTER_ZIPKIN_ENDPOINT")
        url = _hit(env, "GROW_ZIPKIN_URL", "ZIPKIN_URL")
        if endpoint is not None:
            values["endpoint"] = endpoint[1]
        elif url is not None:
            values["endpoint"] = spans_endpoint(url[1])
    else:
        traces = _hit(env, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        base = _hit(env, "OTEL_EXPORTER_OTLP_ENDPOINT")
        if traces is not None:
            values["endpoint"] = traces[1]
        elif base is not None:
            values["endpoint"] = resolve_otlp_endpoint(protocol, base[1])
    timeout = _hit(env, "OTEL_EXPORTER_ZIPKIN_TIMEOUT")
    if timeout is not None:
        name, raw = timeout
        try:
            seconds = parse_duration(raw)
        except ValueError:
            seconds = 0.0
        if seconds <= 0:
            raise ConfigError(f"invalid value in environment variable {name}: expected a duration")
        values["export_timeout_s"] = seconds
    compression = _hit(env, "GROW_ZIPKIN_COMPRESSION")
    if compression is not None:
        name, raw = compression
        if raw.lower() not in ("gzip", "none"):
            raise ConfigError(
                f"invalid value in environment variable {name}: expected gzip or none"
            )
        values["compression"] = raw.lower()
    resource = _hit(env, "OTEL_RESOURCE_ATTRIBUTES")
    attributes: dict[str, str] = {}
    if resource is not None:
        try:
            attributes = _key_values(resource[1], resource[0])
        except UnicodeDecodeError:
            raise ConfigError(
                f"invalid value in environment variable {resource[0]}: bad percent-encoding"
            ) from None
        values["resource_attributes"] = {k: v for k, v in attributes.items() if k != "service.name"}
    service = _hit(env, "OTEL_SERVICE_NAME")
    if service is not None:
        values["service_name"] = service[1]
    elif attributes.get("service.name"):
        values["service_name"] = attributes["service.name"]
    sampler = _hit(env, "OTEL_TRACES_SAMPLER")
    if sampler is not None:
        name, raw = sampler
        lower = raw.lower()
        if lower not in SAMPLERS:
            raise ConfigError(f"invalid value in environment variable {name}: unknown sampler")
        values["sampler"] = lower
        arg = _hit(env, "OTEL_TRACES_SAMPLER_ARG")
        if lower.endswith("traceidratio") and arg is not None:
            try:
                ratio = float(arg[1])
            except ValueError:
                ratio = -1.0
            if not 0.0 <= ratio <= 1.0:
                raise ConfigError(
                    f"invalid value in environment variable {arg[0]}: "
                    "expected a ratio between 0 and 1"
                )
            values["sampler_ratio"] = ratio
    propagators = _hit(env, "OTEL_PROPAGATORS")
    if propagators is not None:
        values["propagators"] = tuple(p.strip() for p in propagators[1].split(",") if p.strip())
    return values


def tracing_config_from_env(
    config: TracingConfig | None = None,
    *,
    env: Mapping[str, str] | None = None,
    **overrides: Any,
) -> ZipkinTracingConfig:
    """A :class:`ZipkinTracingConfig` from the environment beneath ``config``/``overrides``.

    A field of ``config`` counts as explicit when it differs from its class default; keyword
    ``overrides`` (any field) are always explicit; a blank ``service_name`` counts as unset.
    Per field: explicit > environment > preset default.

    ==============================  ======================================================
    Field                           Variables
    ==============================  ======================================================
    ``protocol``                    ``GROW_ZIPKIN_PROTOCOL`` (``native``, ``grpc``,
                                    ``http/protobuf``)
    ``endpoint`` (native)           ``GROW_ZIPKIN_ENDPOINT``,
                                    ``OTEL_EXPORTER_ZIPKIN_ENDPOINT`` (as-is),
                                    ``GROW_ZIPKIN_URL`` / ``ZIPKIN_URL`` + ``/api/v2/spans``
    ``endpoint`` (OTLP, Collector)  ``OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`` (as-is),
                                    ``OTEL_EXPORTER_OTLP_ENDPOINT`` (HTTP appends
                                    ``/v1/traces``)
    ``batch.export_timeout_s``      ``OTEL_EXPORTER_ZIPKIN_TIMEOUT`` (ms or ``10s``)
    ``compression``                 ``GROW_ZIPKIN_COMPRESSION`` (``gzip``, ``none``)
    ``service_name``                ``OTEL_SERVICE_NAME``, else ``service.name`` in
                                    ``OTEL_RESOURCE_ATTRIBUTES``
    ``resource_attributes``         ``OTEL_RESOURCE_ATTRIBUTES``
    ``sampler``, ``sampler_ratio``  ``OTEL_TRACES_SAMPLER``, ``OTEL_TRACES_SAMPLER_ARG``
    ``propagators``                 ``OTEL_PROPAGATORS``
    ==============================  ======================================================

    Headers come from code only (no credential variables exist). ``env`` defaults to
    ``os.environ``. A present but invalid value raises :class:`ConfigError` naming the variable,
    never its value. The result is not validated (``init_tracing`` does that);
    ``service_name`` is ``""`` when nothing sets it.
    """
    explicit = _explicit(config, overrides)
    found = _env_values(os.environ if env is None else env, explicit.get("protocol"))
    timeout = found.pop("export_timeout_s", None)
    values: dict[str, Any] = {**found, **explicit}
    if timeout is not None:
        batch: SpanBatchOptions = values.get("batch", SpanBatchOptions())
        if batch.export_timeout_s == SpanBatchOptions().export_timeout_s:
            values["batch"] = dataclasses.replace(batch, export_timeout_s=timeout)
    values.setdefault("service_name", "")
    return ZipkinTracingConfig(**values)


# --------------------------------------------------------------------------- exporters

_PEM_FILES: list[str] = []


def _cleanup_pem_files() -> None:  # pragma: no cover - runs at interpreter exit
    for path in _PEM_FILES:
        with contextlib.suppress(OSError):
            os.unlink(path)


atexit.register(_cleanup_pem_files)


def _read(path: str | None) -> bytes | None:
    if path is None:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        raise ConfigError("tracing: cannot read a TLS file (check the configured path)") from None


def _otlp_exporter(config: TracingConfig) -> SpanExporter:
    endpoint = config.endpoint
    if endpoint is None or endpoint.rstrip("/").endswith(SPANS_PATH):
        raise ConfigError(
            "tracing: protocol grpc/http/protobuf needs the endpoint of an OpenTelemetry Collector "
            "with a zipkin exporter (stock Zipkin does not accept OTLP)"
        )
    url = resolve_otlp_endpoint(config.protocol, endpoint)
    tls = config.tls or TlsConfig()
    timeout = config.batch.export_timeout_s
    if config.protocol == "grpc":
        return _grpc_exporter(config, url, tls, timeout)
    return _http_exporter(config, url, tls, timeout)


def _grpc_exporter(config: TracingConfig, url: str, tls: TlsConfig, timeout: float) -> SpanExporter:
    try:
        import grpc  # type: ignore[import-untyped]  # noqa: PLC0415 - optional extra
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # noqa: PLC0415
            OTLPSpanExporter as GrpcSpanExporter,
        )
    except ImportError as exc:  # pragma: no cover - depends on the installed extras
        raise ConfigError(
            "tracing: protocol grpc needs the 'grpc' extra (pip install 'grow-zipkin[grpc]')"
        ) from exc
    if tls.insecure_skip_verify:
        raise ConfigError("tracing: insecure_skip_verify is not supported by the gRPC exporter")
    ca = _read(tls.ca_cert_path) or (tls.ca_cert_pem.encode() if tls.ca_cert_pem else None)
    cert = _read(tls.client_cert_path)
    key = _read(tls.client_key_path)
    secure = url.startswith("https:") or ca is not None or cert is not None
    credentials = (
        grpc.ssl_channel_credentials(root_certificates=ca, private_key=key, certificate_chain=cert)
        if secure
        else None
    )
    return GrpcSpanExporter(
        endpoint=url,
        insecure=not secure,
        credentials=credentials,
        headers={k.lower(): v for k, v in config.headers.items()},
        timeout=timeout,
        compression=grpc.Compression.Gzip
        if config.compression == "gzip"
        else grpc.Compression.NoCompression,
    )


def _http_exporter(config: TracingConfig, url: str, tls: TlsConfig, timeout: float) -> SpanExporter:
    import requests  # noqa: PLC0415 - dependency of the OTLP/HTTP exporter
    from opentelemetry.exporter.otlp.proto.http import Compression  # noqa: PLC0415
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter as HttpSpanExporter,
    )

    ca_path = tls.ca_cert_path
    if ca_path is None and tls.ca_cert_pem:
        fd, ca_path = tempfile.mkstemp(prefix="grow-zipkin-ca-", suffix=".pem")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(tls.ca_cert_pem)
        _PEM_FILES.append(ca_path)
    session = None
    if tls.insecure_skip_verify:
        session = requests.Session()
        session.verify = False
    return HttpSpanExporter(
        endpoint=url,
        certificate_file=ca_path,
        client_key_file=tls.client_key_path,
        client_certificate_file=tls.client_cert_path,
        headers=dict(config.headers),
        timeout=timeout,
        compression=Compression.Gzip if config.compression == "gzip" else Compression.NoCompression,
        session=session,
    )


def _merge_tls(base: TlsConfig, extra: TlsConfig | None) -> TlsConfig:
    if extra is None:
        return base
    changes = {
        f.name: getattr(extra, f.name) for f in dataclasses.fields(extra) if getattr(extra, f.name)
    }
    return dataclasses.replace(base, **changes)


def create_zipkin_exporter_factory(
    client_config: ZipkinConfig | None = None,
    *,
    on_export_error: Callable[[BaseException], None] | None = None,
    http_transport: httpx.BaseTransport | None = None,
) -> ExporterFactory:
    """Builds the ``exporter_factory`` of :func:`~grow_zipkin.init_tracing_with`.

    - ``native`` (default) → :class:`~grow_zipkin.ZipkinSpanExporter` to ``config.endpoint``
      (else the client config's endpoint). Headers = client headers + tracing headers; TLS =
      client TLS + tracing TLS; request and total timeout = ``batch.export_timeout_s``;
      ``compression`` (default gzip); proxy, retries, hooks and logger from the client config.
    - ``http/protobuf`` / ``grpc`` → the official OTLP exporter to an OpenTelemetry Collector
      (``config.endpoint`` is required; the Collector's ``zipkin`` exporter forwards to Zipkin).
    """

    def factory(config: TracingConfig) -> SpanExporter:
        if config.protocol != "native":
            return _otlp_exporter(config)
        base = client_config if client_config is not None else ZipkinConfig()
        timeout = config.batch.export_timeout_s
        exporter_config = dataclasses.replace(
            base,
            endpoint=config.endpoint or base.endpoint,
            request_timeout=timeout,
            total_timeout=timeout,
            tls=_merge_tls(base.tls, config.tls),
            headers={**base.headers, **config.headers},
        ).validate()
        return ZipkinSpanExporter(
            config=exporter_config,
            compression=config.compression,
            max_batch_size=config.batch.max_export_batch_size,
            on_error=on_export_error,
            http_transport=http_transport,
        )

    return factory


def init_tracing(
    config: TracingConfig | None = None,
    *,
    env: Mapping[str, str] | Literal[False] | None = None,
    client_config: ZipkinConfig | None = None,
    on_export_error: Callable[[BaseException], None] | None = None,
    propagator_factories: Mapping[str, PropagatorFactory] | None = None,
    span_processors: Sequence[SpanProcessor] = (),
    id_generator: IdGenerator | None = None,
    http_transport: httpx.BaseTransport | None = None,
    **overrides: Any,
) -> ZipkinTracingHandle:
    """Sets up OpenTelemetry tracing for Zipkin (SPEC §5).

    Defaults: protocol ``native`` (Zipkin v2 JSON via :class:`~grow_zipkin.ZipkinSpanExporter`
    to ``http://127.0.0.1:9411/api/v2/spans``), propagators ``tracecontext, baggage, b3``
    composed B3-first so W3C wins on conflict (:func:`order_propagators`). The environment fills
    every field the config leaves unset (see :func:`tracing_config_from_env` and
    :meth:`ZipkinConfig.from_env <grow_zipkin.ZipkinConfig.from_env>`); pass ``env=False`` to
    ignore it.

    Args:
        config: A :class:`~grow_zipkin.TracingConfig` (or :class:`ZipkinTracingConfig`).
        env: Environment beneath the config; default ``os.environ``, ``False`` ignores it.
        client_config: Connection settings of the native exporter (proxy, TLS, retries, hooks,
            logger, headers, endpoint). Default ``ZipkinConfig.from_env()`` (or
            ``ZipkinConfig()`` with ``env=False``).
        on_export_error: Called with every failed native export (the batch is dropped).
        propagator_factories: Extra propagators by name (see ``init_tracing_with``).
        span_processors: Extra span processors added after the batch processor.
        id_generator: Custom id generator.
        http_transport: Test seam of the native exporter.
        **overrides: Any :class:`ZipkinTracingConfig` field (``service_name="checkout"``).
    """
    if env is False:
        explicit = _explicit(config, overrides)
        explicit.setdefault("service_name", "")
        effective = ZipkinTracingConfig(**explicit)
        base = client_config if client_config is not None else ZipkinConfig()
    else:
        effective = tracing_config_from_env(config, env=env, **overrides)
        base = client_config if client_config is not None else ZipkinConfig.from_env(env)
    endpoint = effective.endpoint
    if endpoint is None and effective.protocol == "native":
        endpoint = base.endpoint
    final = dataclasses.replace(
        effective, endpoint=endpoint, propagators=tuple(order_propagators(effective.propagators))
    )
    handle = init_tracing_with(
        final,
        create_zipkin_exporter_factory(
            base, on_export_error=on_export_error, http_transport=http_transport
        ),
        propagator_factories=propagator_factories,
        span_processors=span_processors,
        id_generator=id_generator,
    )
    zipkin_handle = ZipkinTracingHandle(
        handle.config, handle.tracer_provider, handle.propagator, is_global=handle.is_global
    )
    zipkin_handle.endpoint = final.endpoint or ""
    return zipkin_handle
