"""CORS wiring: GET/OPTIONS-only by default (the deliberate historical
posture), POST/PATCH opened only when CORS_ALLOW_WRITES=true — support-console's
connector-admin UI.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from tool_gateway.container import get_container
from tool_gateway.main import create_app
from tool_gateway.settings import Settings

pytestmark = pytest.mark.unit

_ORIGIN = "http://localhost:5174"


def _client(monkeypatch: pytest.MonkeyPatch, **overrides) -> TestClient:
    settings = Settings(tool_gateway_persistence="memory", cors_allowed_origins=_ORIGIN, **overrides)
    monkeypatch.setattr("tool_gateway.main.get_settings", lambda: settings)
    monkeypatch.setattr("tool_gateway.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def _preflight(client: TestClient, method: str):
    return client.options(
        "/connectors",
        headers={"Origin": _ORIGIN, "Access-Control-Request-Method": method},
    )


def test_cors_allow_writes_defaults_false() -> None:
    assert Settings().cors_allow_writes is False


def test_post_preflight_is_denied_without_cors_allow_writes(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _preflight(_client(monkeypatch), "POST")
    assert "POST" not in response.headers.get("access-control-allow-methods", "")


def test_post_preflight_is_allowed_with_cors_allow_writes(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _preflight(_client(monkeypatch, cors_allow_writes=True), "POST")
    assert response.headers.get("access-control-allow-origin") == _ORIGIN
    assert "POST" in response.headers.get("access-control-allow-methods", "")


def test_get_preflight_is_allowed_either_way(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _preflight(_client(monkeypatch), "GET")
    assert response.headers.get("access-control-allow-origin") == _ORIGIN


def test_no_cors_headers_when_no_origin_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings(tool_gateway_persistence="memory", cors_allowed_origins="")
    monkeypatch.setattr("tool_gateway.main.get_settings", lambda: settings)
    monkeypatch.setattr("tool_gateway.container.get_settings", lambda: settings)
    get_container.cache_clear()
    client = TestClient(create_app())

    response = client.options(
        "/connectors", headers={"Origin": _ORIGIN, "Access-Control-Request-Method": "GET"}
    )
    assert "access-control-allow-origin" not in response.headers
