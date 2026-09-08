"""support-console's admin knowledge-ingest UI calls the /internal/memory/v1/
admin surface from a real browser origin — asserts the real CORSMiddleware
wiring, deny-by-default. Mirrors agent-runtime-service's own tests/test_cors.py.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.main import create_app
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.unit

_ORIGIN = "http://localhost:5174"
_ADMIN_DOCUMENTS = "/internal/memory/v1/admin/documents"


def _client(monkeypatch: pytest.MonkeyPatch, cors: str) -> TestClient:
    settings = Settings(memory_persistence="memory", cors_allowed_origins=cors)
    # create_app() reads memoryknowledge.main's own bound name, distinct from the
    # container's — patch both (same lesson as the agent-runtime cors test).
    monkeypatch.setattr("memoryknowledge.main.get_settings", lambda: settings)
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def test_cors_allowed_origins_list_parses_and_trims() -> None:
    assert Settings(cors_allowed_origins=" a , b ,").cors_allowed_origins_list == ["a", "b"]


def test_no_middleware_without_a_configured_origin(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _client(monkeypatch, "").options(
        _ADMIN_DOCUMENTS,
        headers={"Origin": _ORIGIN, "Access-Control-Request-Method": "POST"},
    )
    assert "access-control-allow-origin" not in response.headers


def test_configured_origin_gets_a_real_post_preflight(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _client(monkeypatch, _ORIGIN).options(
        _ADMIN_DOCUMENTS,
        headers={
            "Origin": _ORIGIN,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type,x-actor-id",
        },
    )
    assert response.headers.get("access-control-allow-origin") == _ORIGIN
    assert "POST" in response.headers.get("access-control-allow-methods", "")


def test_an_unconfigured_origin_is_not_reflected(monkeypatch: pytest.MonkeyPatch) -> None:
    response = _client(monkeypatch, _ORIGIN).options(
        _ADMIN_DOCUMENTS,
        headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"},
    )
    assert response.headers.get("access-control-allow-origin") != "http://evil.example"
