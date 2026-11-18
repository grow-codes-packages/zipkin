"""Factories the integration suite and the conformance runner use (owned by this repository).

sdk-kit generates this file once. Replace the bodies when the service needs more setup (extra
credentials from /bootstrap/env, namespaces, a provider built on the client …) and keep the
capability sets honest: every capability left out skips scenarios, and each skip must be
explained in docs/compatibility.md.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import grow_zipkin as sdk

URL_VAR = "GROW_ZIPKIN_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = {"version"}


def core_client(env: Mapping[str, str]) -> sdk.ZipkinClient:
    """A client configured from the test environment (URL, token, … from /bootstrap/env)."""
    return sdk.ZipkinClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncZipkinClient:
    return sdk.AsyncZipkinClient.from_env(env)


#: Capabilities for the tracing scenarios: query, services, dependencies.
TRACING_CAPABILITIES: set[str] = {"query", "services"}


def tracing_target(env: Mapping[str, str], scenario: Mapping[str, Any]) -> Any | None:
    """A ``testing.TracingTarget(handle, query)`` for one scenario; ``None`` skips the suite."""
    return None
