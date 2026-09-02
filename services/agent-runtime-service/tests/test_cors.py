"""domain 09/10's own frontends call conversation_router directly from a real
browser origin — Settings.cors_allowed_origins/main.create_app's own docstrings.
Asserts the real CORSMiddleware wiring, not just that the setting parses.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from agentruntime.container import get_container
from agentruntime.main import create_app
from agentruntime.settings import Settings

pytestmark = pytest.mark.unit


@pytest.fixture
def client_without_cors(monkeypatch: pytest.MonkeyPatch):
    settings = Settings(agent_runtime_persistence="memory")
    # create_app() itself reads agentruntime.main's own bound `get_settings` name
    # (a `from ... import` reference, distinct from agentruntime.container's own) —
    # patching only the container's copy (as most other tests here do, since only
    # container-resolved settings matter to them) silently leaves create_app()'s own
    # CORS decision reading the real, unpatched, lru_cached settings instead.
    monkeypatch.setattr("agentruntime.main.get_settings", lambda: settings)
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


@pytest.fixture
def client_with_cors(monkeypatch: pytest.MonkeyPatch):
    settings = Settings(agent_runtime_persistence="memory", cors_allowed_origins="http://localhost:5173,http://localhost:5174")
    monkeypatch.setattr("agentruntime.main.get_settings", lambda: settings)
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def test_cors_allowed_origins_list_parses_and_trims_a_comma_separated_setting() -> None:
    settings = Settings(cors_allowed_origins=" http://localhost:5173 ,http://localhost:5174,")
    assert settings.cors_allowed_origins_list == ["http://localhost:5173", "http://localhost:5174"]


def test_empty_cors_setting_parses_to_an_empty_list() -> None:
    assert Settings(cors_allowed_origins="").cors_allowed_origins_list == []


def test_no_middleware_is_added_when_no_origin_is_configured(client_without_cors: TestClient) -> None:
    response = client_without_cors.options(
        "/api/v1/conversations",
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
    )

    assert "access-control-allow-origin" not in response.headers


def test_a_configured_origin_gets_a_real_preflight_response(client_with_cors: TestClient) -> None:
    response = client_with_cors.options(
        "/api/v1/conversations",
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
    )

    assert response.headers["access-control-allow-origin"] == "http://localhost:5173"


def test_an_unconfigured_origin_is_not_reflected_back(client_with_cors: TestClient) -> None:
    response = client_with_cors.options(
        "/api/v1/conversations",
        headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"},
    )

    assert "access-control-allow-origin" not in response.headers
