"""Errors, redaction, Secret, env parsing, config and retry policy."""

from __future__ import annotations

import asyncio
import datetime as dt
import logging

import pytest

import grow_zipkin as sdk
from _support import make_config

# --------------------------------------------------------------------------- errors


def test_error_codes_and_classes() -> None:
    expected = {
        "CONFIG": (sdk.ConfigError, False),
        "AUTHENTICATION": (sdk.AuthenticationError, False),
        "PERMISSION_DENIED": (sdk.PermissionDeniedError, False),
        "NOT_FOUND": (sdk.NotFoundError, False),
        "CONFLICT": (sdk.ConflictError, False),
        "RATE_LIMITED": (sdk.RateLimitError, True),
        "VALIDATION": (sdk.ValidationError, False),
        "UNAVAILABLE": (sdk.UnavailableError, True),
        "TIMEOUT": (sdk.TimeoutError, True),
        "UNSUPPORTED": (sdk.UnsupportedError, False),
        "CANCELLED": (sdk.CancelledError, False),
    }
    for code, (cls, retryable) in expected.items():
        assert sdk.error_for_code(code) is cls
        err = cls("op: boom")
        assert err.code == sdk.ErrorCode(code)
        assert str(err.code) == code
        assert err.retryable is retryable
        assert isinstance(err, sdk.ZipkinError)
        assert isinstance(err, sdk.ServiceError)
        assert str(err) == "op: boom"


def test_error_attributes_and_repr() -> None:
    cause = ValueError("inner")
    err = sdk.RateLimitError(
        "op: slow down (HTTP 429)",
        http_status=429,
        request_id="r-1",
        retry_after=2.5,
        cause=cause,
        retryable=False,
    )
    assert err.cause is cause
    assert err.__cause__ is cause
    assert err.retryable is False
    text = repr(err)
    assert "RATE_LIMITED" in text
    assert "http_status=429" in text
    assert "request_id='r-1'" in text
    assert "retry_after=2.5" in text
    assert repr(sdk.NotFoundError("x")) == (
        "NotFoundError(code=NOT_FOUND, message='x', retryable=False)"
    )
    assert sdk.NotFoundError("x").cause is None


def test_cancelled_error_is_asyncio_cancelled() -> None:
    err = sdk.CancelledError("op: cancelled")
    assert isinstance(err, asyncio.CancelledError)
    assert isinstance(err, sdk.ZipkinError)


# --------------------------------------------------------------------------- redaction


def test_secret_never_prints() -> None:
    secret = sdk.Secret("hunter2")
    assert secret.reveal() == "hunter2"
    assert str(secret) == "[REDACTED]"
    assert repr(secret) == "Secret('[REDACTED]')"
    assert f"{secret}" == "[REDACTED]"
    assert f"{secret:>12}" == "  [REDACTED]"
    assert bool(secret) and len(secret) == 7
    assert not sdk.Secret("")
    assert secret == sdk.Secret("hunter2")
    assert secret != sdk.Secret("other")
    assert secret != "hunter2"
    assert hash(secret) == hash(sdk.Secret("hunter2"))
    assert sdk.Secret(secret).reveal() == "hunter2"


def test_redact_headers() -> None:
    headers = {
        "Authorization": "Bearer abc",
        "Proxy-Authorization": "x",
        "Cookie": "a=b",
        "Set-Cookie": "c=d",
        "X-Vault-Token": "t",
        "X-Api-Key": "k",
        "X-Session-Id": "s",
        "X-Amz-Signature": "sig",
        "X-Credential": "c",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }
    out = sdk.redact_headers(headers)
    assert out["Accept"] == "application/json"
    assert out["Content-Type"] == "application/json"
    for name in headers:
        if name not in ("Accept", "Content-Type"):
            assert out[name] == "[REDACTED]", name
    assert sdk.redact_headers([("X-Token", "a"), ("Accept", "b")]) == {
        "X-Token": "[REDACTED]",
        "Accept": "b",
    }


def test_redact_url() -> None:
    assert (
        sdk.redact_url("https://user:pw@example.com/v1/x?token=abc&page=2#frag")
        == "https://[REDACTED]@example.com/v1/x?token=[REDACTED]&page=[REDACTED]#frag"
    )
    assert sdk.redact_url("http://h/p") == "http://h/p"
    assert sdk.redact_url("http://[::1") == "[REDACTED]"


def test_redact_fields_recursive() -> None:
    data = {
        "user": "bob",
        "password": "p",
        "nested": {"api_key": "k", "ok": 1, "list": [{"client_secret": "s"}, "plain"]},
        "tuple": ("a", {"Session": "x"}),
        "wrapped": sdk.Secret("w"),
        "custom_field": "c",
    }
    out = sdk.redact_fields(data, extra_keys=["CUSTOM"])
    assert out == {
        "user": "bob",
        "password": "[REDACTED]",
        "nested": {
            "api_key": "[REDACTED]",
            "ok": 1,
            "list": [{"client_secret": "[REDACTED]"}, "plain"],
        },
        "tuple": ["a", {"Session": "[REDACTED]"}],
        "wrapped": "[REDACTED]",
        "custom_field": "[REDACTED]",
    }


def test_redact_text_and_truncate() -> None:
    text = "token abcd1234 and abcd1234XYZ and abc"
    out = sdk.redact_text(text, ["abcd1234", "abcd1234XYZ", "abc", None, sdk.Secret("and ")])
    assert "abcd1234" not in out
    assert out.count("[REDACTED]") == 4
    assert out.endswith("abc")  # 3 characters is below the minimum length
    assert sdk.truncate("x" * 10, 5) == "xxxx…"
    assert sdk.truncate("short", 300) == "short"


def test_config_repr_redacts_credentials() -> None:
    """core.redaction.config"""
    cfg = sdk.ZipkinConfig(
        url="https://user:pw@svc.example/",
        token="super-secret-token",
        headers={"X-Api-Key": "k-123", "Accept": "a"},
        proxy="http://proxyuser:proxypass@proxy:3128",
        tls=sdk.TlsConfig(ca_cert_pem="PEM", client_cert_path="/c", client_key_path="/k"),
    )
    for text in (repr(cfg), str(cfg)):
        assert "super-secret-token" not in text
        assert "k-123" not in text
        assert "proxypass" not in text
        assert ":pw@" not in text
        assert "[REDACTED]" in text
        assert "TlsConfig(" in text
    assert isinstance(cfg.token, sdk.Secret)
    assert cfg.known_secrets() == ["super-secret-token"]
    assert sdk.ZipkinConfig().known_secrets() == []


def test_safe_repr_handles_sensitive_names() -> None:
    import dataclasses

    from grow_zipkin._core.redaction import safe_repr

    @dataclasses.dataclass
    class Example:
        api_token: str = "s3cr3t-value"
        hidden: str = dataclasses.field(default="h", repr=False)
        endpoint: str = "http://u:p@h/x"
        count: int = 1

    text = safe_repr(Example())
    assert "s3cr3t-value" not in text and "hidden" not in text and "u:p" not in text
    assert "count=1" in text


# --------------------------------------------------------------------------- env


@pytest.mark.parametrize(
    ("text", "seconds"),
    [
        ("1500", 1.5),
        ("0", 0.0),
        ("500ms", 0.5),
        ("10s", 10.0),
        ("2m", 120.0),
        ("1h", 3600.0),
        ("1m30s", 90.0),
        ("1.5s", 1.5),
        (".5s", 0.5),
        ("100us", 0.0001),
        ("100µs", 0.0001),
        ("10ns", 1e-8),
        (" 3s ", 3.0),
    ],
)
def test_parse_duration(text: str, seconds: float) -> None:
    assert sdk.parse_duration(text) == pytest.approx(seconds)


@pytest.mark.parametrize("text", ["", "abc", "10x", "s", "1s2", "-1s", "1h 2m"])
def test_parse_duration_invalid(text: str) -> None:
    with pytest.raises(ValueError):
        sdk.parse_duration(text)


def test_parse_bool() -> None:
    for t in ("true", "TRUE", "1", "yes", "On"):
        assert sdk.parse_bool(t) is True
    for f in ("false", "0", "no", "OFF"):
        assert sdk.parse_bool(f) is False
    with pytest.raises(ValueError):
        sdk.parse_bool("maybe")


def test_to_seconds() -> None:
    assert sdk.to_seconds(dt.timedelta(milliseconds=250)) == 0.25
    assert sdk.to_seconds(3) == 3.0


def test_env_reader() -> None:
    p = sdk.ENV_PREFIX
    env = {
        p + "URL": "http://a",
        "VENDOR_URL": "http://v",
        p + "EMPTY": "",
        p + "D": "2s",
        p + "B": "yes",
        p + "I": " 7 ",
        p + "F": "1.25",
        p + "BAD_D": "soon",
        p + "BAD_B": "perhaps",
        p + "BAD_I": "seven",
        p + "BAD_F": "nan",
        p + "BAD_F2": "x",
        "ONLY_VENDOR": "vv",
    }
    r = sdk.EnvReader(env)
    assert r.string("URL", ["VENDOR_URL"]) == "http://a"
    assert r.string("MISSING", ["ONLY_VENDOR"]) == "vv"
    assert r.string("EMPTY") is None
    assert r.lookup("EMPTY", ["VENDOR_URL"]) == ("VENDOR_URL", "http://v")
    assert r.duration("D") == 2.0
    assert r.duration("NONE") is None
    assert r.bool("B") is True
    assert r.bool("NONE") is None
    assert r.int("I") == 7
    assert r.int("NONE") is None
    assert r.float("F") == 1.25
    assert r.float("NONE") is None
    for method, name in (
        (r.duration, "BAD_D"),
        (r.bool, "BAD_B"),
        (r.int, "BAD_I"),
        (r.float, "BAD_F"),
        (r.float, "BAD_F2"),
    ):
        with pytest.raises(sdk.ConfigError) as info:
            method(name)
        assert p + name in str(info.value)
        assert env[p + name] not in str(info.value)


def test_env_reader_defaults_to_os_environ(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", "http://from-os")
    assert sdk.EnvReader().string("URL") == "http://from-os"


def test_config_env_precedence() -> None:
    """core.config.env-precedence"""
    p = sdk.ENV_PREFIX

    class VendorConfig(sdk.ZipkinConfig):
        ENV_VENDOR = {"URL": ("VENDOR_ADDR", "OTHER_ADDR"), "TOKEN": ("VENDOR_TOKEN",)}

    # default
    assert VendorConfig.from_env({}).url == sdk.DEFAULT_URL
    # vendor (first listed wins)
    env = {"OTHER_ADDR": "http://other:1", "VENDOR_ADDR": "http://vendor:1"}
    assert VendorConfig.from_env(env).url == "http://vendor:1"
    # GROW_* beats vendor
    env[p + "URL"] = "http://grow:1"
    assert VendorConfig.from_env(env).url == "http://grow:1"
    # explicit beats everything
    assert VendorConfig.from_env(env, url="http://explicit:1").url == "http://explicit:1"
    # token from vendor, wrapped in Secret
    cfg = VendorConfig.from_env({"VENDOR_TOKEN": "vt-123456"})
    assert isinstance(cfg.token, sdk.Secret)
    assert cfg.token.reveal() == "vt-123456"
    # invalid value → ConfigError naming the variable only
    with pytest.raises(sdk.ConfigError) as info:
        VendorConfig.from_env({p + "REQUEST_TIMEOUT": "fast-please"})
    assert p + "REQUEST_TIMEOUT" in str(info.value)
    assert "fast-please" not in str(info.value)


def test_config_from_env_common_fields() -> None:
    p = sdk.ENV_PREFIX
    env = {
        p + "URL": "https://svc:8443",
        p + "TOKEN": "tok-12345",
        p + "CONNECT_TIMEOUT": "1500",
        p + "REQUEST_TIMEOUT": "5s",
        p + "TOTAL_TIMEOUT": "1m",
        p + "MAX_RETRIES": "5",
        p + "CA_CERT": "/ca.pem",
        p + "CLIENT_CERT": "/c.pem",
        p + "CLIENT_KEY": "/k.pem",
        p + "INSECURE_SKIP_VERIFY": "true",
        p + "PROXY": "http://proxy:3128",
    }
    cfg = sdk.ZipkinConfig.from_env(env)
    assert cfg.url == "https://svc:8443"
    assert cfg.token is not None and cfg.token.reveal() == "tok-12345"
    assert (cfg.connect_timeout, cfg.request_timeout, cfg.total_timeout) == (1.5, 5.0, 60.0)
    assert cfg.retry.max_retries == 5
    assert cfg.tls == sdk.TlsConfig(
        ca_cert_path="/ca.pem",
        client_cert_path="/c.pem",
        client_key_path="/k.pem",
        insecure_skip_verify=True,
    )
    assert cfg.proxy == "http://proxy:3128"
    explicit = sdk.ZipkinConfig.from_env(env, retry=sdk.RetryPolicy(max_retries=0))
    assert explicit.retry.max_retries == 0


def test_config_defaults_and_timedelta() -> None:
    cfg = sdk.ZipkinConfig(connect_timeout=dt.timedelta(seconds=2))
    assert cfg.connect_timeout == 2.0
    assert cfg.request_timeout == 30.0
    assert cfg.total_timeout == 120.0
    assert cfg.retry == sdk.RetryPolicy()
    assert cfg.validate() is cfg
    with pytest.raises(TypeError):
        cfg.headers["x"] = "y"  # type: ignore[index]


@pytest.mark.parametrize(
    ("kwargs", "fragment"),
    [
        ({"url": "ftp://host"}, "url"),
        ({"url": "http://"}, "url"),
        ({"url": "http://[::1"}, "url"),
        ({"connect_timeout": 0}, "connect_timeout"),
        ({"request_timeout": -1}, "request_timeout"),
        ({"total_timeout": 0}, "total_timeout"),
        ({"retry": sdk.RetryPolicy(max_retries=-1)}, "max_retries"),
        ({"retry": sdk.RetryPolicy(initial_backoff=-1)}, "backoff"),
        ({"retry": sdk.RetryPolicy(multiplier=0.5)}, "multiplier"),
        ({"tls": sdk.TlsConfig(client_cert_path="/c")}, "together"),
        ({"proxy": "nope"}, "proxy"),
        ({"token": "   "}, "token"),
    ],
)
def test_config_validate(kwargs: dict[str, object], fragment: str) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.ZipkinConfig(**kwargs).validate()  # type: ignore[arg-type]
    assert fragment in str(info.value)


def test_config_logger_field_accepts_stdlib_logger() -> None:
    cfg = make_config("http://h", logger=logging.getLogger("x"))
    assert cfg.logger is not None


# --------------------------------------------------------------------------- retry


def test_retry_policy_backoff_full_jitter() -> None:
    values = iter([0.0, 0.5, 0.999])
    policy = sdk.RetryPolicy(random=lambda: next(values))
    assert policy.max_attempts == 4
    assert policy.backoff(0) == 0.0
    assert policy.backoff(1) == pytest.approx(0.5 * 0.2)
    assert policy.backoff(2) == pytest.approx(0.999 * 0.4)
    assert policy.backoff_cap(20) == 10.0
    assert policy.backoff_cap(100000) == 10.0
    assert policy.with_max_retries(1).max_retries == 1
    td = sdk.RetryPolicy(initial_backoff=dt.timedelta(milliseconds=5))  # type: ignore[arg-type]
    assert td.initial_backoff == 0.005


def test_parse_retry_after() -> None:
    assert sdk.parse_retry_after(None) is None
    assert sdk.parse_retry_after("  ") is None
    assert sdk.parse_retry_after("3") == 3.0
    assert sdk.parse_retry_after("-3") == 0.0
    assert sdk.parse_retry_after("nan") is None
    assert sdk.parse_retry_after("soon") is None
    now = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc).timestamp()
    assert sdk.parse_retry_after("Thu, 01 Jan 2026 00:00:30 GMT", now=lambda: now) == 30.0
    assert sdk.parse_retry_after("Thu, 01 Jan 2026 00:00:30", now=lambda: now) == 30.0
