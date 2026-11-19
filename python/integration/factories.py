"""Factories the integration suite and the conformance runner use (owned by this repository).

Zipkin has no authentication, so the client needs only ``GROW_ZIPKIN_URL`` (from the compose
environment / ``/bootstrap/env``). In-memory storage makes spans queryable as soon as
``POST /api/v2/spans`` returns, so no warm-up is needed; names are lower-case because Zipkin
lower-cases them on ingest.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from typing import Any

import grow_zipkin as sdk
from grow_zipkin.testing import TracingTarget

URL_VAR = "GROW_ZIPKIN_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = {"version"}

#: Capabilities for the tracing scenarios: query + services (dependencies have no scenario).
TRACING_CAPABILITIES: set[str] = {"query", "services"}

#: ``${serviceName}`` of the tracing scenarios (unique per run, so stale names never match).
CONFORMANCE_SERVICE = (
    "conformance-py-"
    + "".join(c for c in os.environ.get("GROW_TEST_RUN_ID", "local").lower() if c.isalnum())[:24]
)


def core_client(env: Mapping[str, str]) -> sdk.ZipkinClient:
    """A client configured from the test environment."""
    return sdk.ZipkinClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncZipkinClient:
    return sdk.AsyncZipkinClient.from_env(env)


def tracing_target(env: Mapping[str, str], scenario: Mapping[str, Any]) -> TracingTarget:
    """A ``TracingTarget(handle, query)``: the native exporter + the client's query API."""
    client = core_client(env)
    handle = client.init_tracing(service_name=CONFORMANCE_SERVICE, set_global=False, env=dict(env))
    return TracingTarget(handle, client.query)
