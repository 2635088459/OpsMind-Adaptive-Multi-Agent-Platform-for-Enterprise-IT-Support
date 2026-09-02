"""SPEC-SC-015: support-console is this service's first browser caller — verifies the
real CORS behavior directly (not via the module-level, lru_cache'd app/get_settings
singletons `main.py` exposes) so an allowed vs. a non-allowed origin is genuinely
exercised without fighting that cache.
"""

from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.testclient import TestClient

from evaluationimprovement.settings import Settings


def _app_with_cors(cors_allowed_origins: str) -> TestClient:
    settings = Settings(cors_allowed_origins=cors_allowed_origins)
    app = FastAPI()
    if settings.cors_allowed_origins_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allowed_origins_list,
            allow_methods=["GET", "POST", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "traceparent"],
            allow_credentials=False,
        )

    @app.get("/evaluation/runs/{run_id}")
    def _run(run_id: str) -> dict[str, str]:
        return {"run_id": run_id}

    return TestClient(app)


@pytest.mark.unit
def test_an_allowed_origin_gets_the_real_cors_header() -> None:
    client = _app_with_cors("http://localhost:5174")

    response = client.get("/evaluation/runs/run-1", headers={"Origin": "http://localhost:5174"})

    assert response.headers.get("access-control-allow-origin") == "http://localhost:5174"


@pytest.mark.unit
def test_a_non_allowed_origin_gets_no_cors_header() -> None:
    client = _app_with_cors("http://localhost:5174")

    response = client.get("/evaluation/runs/run-1", headers={"Origin": "http://evil.example"})

    assert "access-control-allow-origin" not in response.headers


@pytest.mark.unit
def test_empty_cors_config_is_deny_by_default_no_middleware_installed() -> None:
    client = _app_with_cors("")

    response = client.get("/evaluation/runs/run-1", headers={"Origin": "http://localhost:5174"})

    assert "access-control-allow-origin" not in response.headers
