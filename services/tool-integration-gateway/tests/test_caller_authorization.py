"""SPEC-SC-018/020 follow-up: this router had ZERO caller-identity check at all
before this — every write endpoint (submit/decide/cancel) was reachable by anyone
who could reach this service's own port. Verifies the real, added
X-Caller-Id/X-Caller-Type gate (api.security) directly over HTTP, mirroring
tests/test_app.py's own real-FastAPI-app convention rather than mocking the
dependency away.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from tool_gateway.container import get_container
from tool_gateway.main import create_app
from tool_gateway.settings import Settings


@pytest.fixture()
def anonymous_client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """No default headers at all — the negative-case client. Real submit/decide/
    cancel calls in this file always carry their own explicit headers instead."""
    monkeypatch.setattr("tool_gateway.container.get_settings", lambda: Settings(tool_gateway_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def _submit_body() -> dict:
    return {
        "idempotency_key": str(uuid.uuid4()), "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate crash loop",
        "correlation_id": str(uuid.uuid4()),
    }


def test_submit_with_no_caller_headers_at_all_is_rejected(anonymous_client: TestClient) -> None:
    response = anonymous_client.post("/internal/tool-gateway/v1/tool-requests", json=_submit_body())

    # This service's own shared RequestValidationError handler maps a missing
    # required header to 400 VALIDATION_FAILED, not FastAPI's bare default 422.
    assert response.status_code == 400


def test_submit_from_a_non_service_caller_type_is_rejected(anonymous_client: TestClient) -> None:
    response = anonymous_client.post(
        "/internal/tool-gateway/v1/tool-requests", json=_submit_body(),
        headers={"X-Caller-Id": "some-browser-session", "X-Caller-Type": "HUMAN"},
    )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "UNTRUSTED_CALLER"


def test_submit_from_a_real_service_caller_succeeds(anonymous_client: TestClient) -> None:
    response = anonymous_client.post(
        "/internal/tool-gateway/v1/tool-requests", json=_submit_body(),
        headers={"X-Caller-Id": "agent-runtime-service", "X-Caller-Type": "SERVICE"},
    )

    assert response.status_code == 200


def test_cancel_and_approval_decision_also_require_a_real_service_caller(anonymous_client: TestClient) -> None:
    submitted = anonymous_client.post(
        "/internal/tool-gateway/v1/tool-requests", json=_submit_body(),
        headers={"X-Caller-Id": "agent-runtime-service", "X-Caller-Type": "SERVICE"},
    ).json()
    tool_request_id = submitted["tool_request_id"]

    cancel_denied = anonymous_client.post(
        f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}/cancel",
        json={"idempotency_key": str(uuid.uuid4()), "requested_by": "some-browser-session", "reason": "no longer needed", "correlation_id": str(uuid.uuid4())},
        headers={"X-Caller-Id": "some-browser-session", "X-Caller-Type": "HUMAN"},
    )
    assert cancel_denied.status_code == 403

    decision_denied = anonymous_client.post(
        f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}/approval-decisions",
        json={"approved": True, "decided_by": "some-browser-session", "correlation_id": str(uuid.uuid4()), "denial_reason": None},
        headers={"X-Caller-Id": "some-browser-session", "X-Caller-Type": "HUMAN"},
    )
    assert decision_denied.status_code == 403


def test_reading_a_tool_request_stays_open_to_a_caller_asserting_no_identity_at_all(anonymous_client: TestClient) -> None:
    """SPEC-SC-006/018: this is the one read support-console's own AiLogPanel
    needs — deliberately NOT gated behind require_service_caller."""
    submitted = anonymous_client.post(
        "/internal/tool-gateway/v1/tool-requests", json=_submit_body(),
        headers={"X-Caller-Id": "agent-runtime-service", "X-Caller-Type": "SERVICE"},
    ).json()
    tool_request_id = submitted["tool_request_id"]

    response = anonymous_client.get(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}")

    assert response.status_code == 200
    assert response.json()["tool_request_id"] == tool_request_id
