"""SPEC-ARO-037/038/043 (phase-10 Conversational Intake), driven through the real
FastAPI app: POST /api/v1/conversations creates a real ticket (mocked at the HTTP
transport, never a live 02-ticket-workflow instance — see
tests/infrastructure/test_ticket_workflow_client.py for the adapter-level contract
test) then a real conversational_intake WorkflowInstance, reachable afterward through
the existing, unmodified /internal/agent-runtime/v1/workflows query surface.
"""

from __future__ import annotations

import base64
import json
import uuid

import httpx
import pytest
from fastapi.testclient import TestClient

from agentruntime.application.records import ReasoningOutcome
from agentruntime.container import get_container
from agentruntime.domain.ids import WorkflowInstanceId
from agentruntime.main import create_app
from agentruntime.settings import Settings

pytestmark = pytest.mark.unit


def _fake_jwt(sub: str) -> str:
    """An unsigned, structurally-valid JWT — this service never verifies the
    signature itself (see interfaces.conversation.security's own docstring for why),
    only reads `sub` from the payload segment.
    """
    header = base64.urlsafe_b64encode(json.dumps({"alg": "none"}).encode()).rstrip(b"=").decode()
    payload = base64.urlsafe_b64encode(json.dumps({"sub": sub}).encode()).rstrip(b"=").decode()
    return f"{header}.{payload}.fake-signature"


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(agent_runtime_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


@pytest.fixture
def escalation_client(monkeypatch: pytest.MonkeyPatch):
    """SPEC-ARO-041: escalation routing is unconfigured by default (Settings' own safe
    blank default) — this fixture configures it so escalation tests can exercise the
    real triage call path end to end.
    """
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(
        agent_runtime_persistence="memory", escalation_default_category_id="cat-1",
        escalation_default_support_queue_id="queue-1", escalation_default_priority="HIGH",
        escalation_default_team_name="IT Support",
    ))
    get_container.cache_clear()
    return TestClient(create_app())


def _mock_ticket_workflow(container, handler) -> None:
    container.ticket_workflow_client._http_client = httpx.Client(transport=httpx.MockTransport(handler))


def _ticket_workflow_handler(ticket_id: uuid.UUID, resolution_cycle_id: uuid.UUID):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(201, json={
            "ticketId": str(ticket_id), "displayId": "INC-9000", "status": "NEW",
            "createdAt": "2026-09-01T00:00:00Z", "version": 0, "resolutionCycleId": str(resolution_cycle_id),
        })
    return handler


def test_start_conversation_creates_a_real_ticket_then_a_conversational_intake_workflow(client: TestClient) -> None:
    container = get_container()
    ticket_id, resolution_cycle_id = uuid.uuid4(), uuid.uuid4()
    _mock_ticket_workflow(container, _ticket_workflow_handler(ticket_id, resolution_cycle_id))

    response = client.post(
        "/api/v1/conversations",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-1')}", "Idempotency-Key": "conv-app-1"},
    )

    assert response.status_code == 201
    body = response.json()
    conversation_id = body["conversation_id"]
    assert body["started_at"]

    workflow = client.get(f"/internal/agent-runtime/v1/workflows/{conversation_id}")
    assert workflow.status_code == 200
    assert workflow.json()["state"] == "RUNNING"

    by_ticket = client.get(f"/internal/agent-runtime/v1/workflows/by-ticket/{ticket_id}")
    assert by_ticket.status_code == 200
    assert len(by_ticket.json()) == 1
    assert by_ticket.json()[0]["workflow_instance_id"] == conversation_id


def test_start_conversation_is_idempotent_and_never_creates_a_second_ticket(client: TestClient) -> None:
    container = get_container()
    ticket_id, resolution_cycle_id = uuid.uuid4(), uuid.uuid4()
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(201, json={
            "ticketId": str(ticket_id), "displayId": "INC-9001", "status": "NEW",
            "createdAt": "2026-09-01T00:00:00Z", "version": 0, "resolutionCycleId": str(resolution_cycle_id),
        })

    _mock_ticket_workflow(container, handler)
    headers = {"Authorization": f"Bearer {_fake_jwt('employee-2')}", "Idempotency-Key": "conv-app-2"}

    first = client.post("/api/v1/conversations", headers=headers)
    second = client.post("/api/v1/conversations", headers=headers)

    assert first.status_code == 201
    assert second.status_code == 201
    assert first.json()["conversation_id"] == second.json()["conversation_id"]
    assert call_count == 1


def test_start_conversation_requires_a_bearer_authorization_header(client: TestClient) -> None:
    # A missing required header is FastAPI's own RequestValidationError, mapped by this
    # app's existing generic handler to 400 VALIDATION_ERROR — the same as any other
    # missing-required-field request, not a distinct auth-specific status.
    response = client.post("/api/v1/conversations", headers={"Idempotency-Key": "conv-app-3"})

    assert response.status_code == 400


def test_start_conversation_rejects_a_non_bearer_authorization_header(client: TestClient) -> None:
    response = client.post(
        "/api/v1/conversations", headers={"Authorization": "Basic dXNlcjpwYXNz", "Idempotency-Key": "conv-app-3b"},
    )

    assert response.status_code == 401


def test_pausing_and_resuming_a_conversation_preserves_its_conversation_specific_fields(client: TestClient) -> None:
    """Regression test: WorkflowInstanceRecord's own requester_subject/ticket_version/
    ticket_display_id fields are defaulted (not required, unlike current_checkpoint_id/
    completed_at) — every pre-existing service that reconstructs a fresh
    WorkflowInstanceRecord rather than dataclasses.replace()-ing the current one must
    now explicitly carry these 3 fields forward, or a conversation's own identity is
    silently wiped the moment any of pause/resume/cancel/fail/complete touches it.
    """
    container = get_container()
    ticket_id, resolution_cycle_id = uuid.uuid4(), uuid.uuid4()
    _mock_ticket_workflow(container, _ticket_workflow_handler(ticket_id, resolution_cycle_id))

    started = client.post(
        "/api/v1/conversations",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-4')}", "Idempotency-Key": "conv-app-5"},
    )
    conversation_id = started.json()["conversation_id"]

    paused = client.post(
        f"/internal/agent-runtime/v1/workflows/{conversation_id}/pause", json={"idempotency_key": "pause-conv-1"},
    )
    assert paused.status_code == 200

    after_pause = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(conversation_id)))
    assert after_pause.requester_subject == "employee-4"
    assert after_pause.ticket_version == 0
    assert after_pause.ticket_display_id == "INC-9000"

    resumed = client.post(
        f"/internal/agent-runtime/v1/workflows/{conversation_id}/resume", json={"idempotency_key": "resume-conv-1"},
    )
    assert resumed.status_code == 200

    after_resume = container.workflow_instance_repository.find_by_id(after_pause.id)
    assert after_resume.requester_subject == "employee-4"
    assert after_resume.ticket_version == 0
    assert after_resume.ticket_display_id == "INC-9000"


def test_start_conversation_surfaces_a_downstream_ticket_creation_failure_as_a_bad_gateway(client: TestClient) -> None:
    container = get_container()

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "internal"})

    _mock_ticket_workflow(container, handler)

    response = client.post(
        "/api/v1/conversations",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-3')}", "Idempotency-Key": "conv-app-4"},
    )

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "TICKET_CREATION_FAILED"


def _ticket_workflow_router_handler(ticket_id: uuid.UUID, resolution_cycle_id: uuid.UUID):
    """SPEC-ARO-041: HttpTicketWorkflowClient shares one httpx.Client for both
    create_ticket() and triage_ticket() — this handler dispatches on path so a single
    mock transport can serve both calls in one conversation's own end-to-end flow.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/triage"):
            return httpx.Response(200, json={"ticketId": str(ticket_id), "version": 1})
        return httpx.Response(201, json={
            "ticketId": str(ticket_id), "displayId": "INC-9000", "status": "NEW",
            "createdAt": "2026-09-01T00:00:00Z", "version": 0, "resolutionCycleId": str(resolution_cycle_id),
        })

    return handler


def _start_conversation(client: TestClient, container, sub: str, idempotency_key: str) -> str:
    ticket_id, resolution_cycle_id = uuid.uuid4(), uuid.uuid4()
    _mock_ticket_workflow(container, _ticket_workflow_router_handler(ticket_id, resolution_cycle_id))
    started = client.post(
        "/api/v1/conversations", headers={"Authorization": f"Bearer {_fake_jwt(sub)}", "Idempotency-Key": idempotency_key},
    )
    assert started.status_code == 201
    return started.json()["conversation_id"]


def test_send_message_with_a_plain_question_returns_a_text_reply(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-5", "conv-msg-1")

    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        json={"text": "my monitor is flickering"},
        headers={"Authorization": f"Bearer {_fake_jwt('employee-5')}", "Idempotency-Key": "msg-1"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["type"] == "text"
    assert body["text"]


def test_send_message_with_a_password_reset_request_returns_a_proposed_action(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-6", "conv-msg-2")

    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        json={"text": "I need to reset my password"},
        headers={"Authorization": f"Bearer {_fake_jwt('employee-6')}", "Idempotency-Key": "msg-2"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["type"] == "proposedAction"
    assert body["action_id"]
    assert body["requires_confirmation"] is True


class _FakeServiceTokenProvider:
    def get_token(self) -> str:
        return "service-identity-token"


def test_send_message_with_a_hardware_issue_escalates_the_real_ticket(escalation_client: TestClient) -> None:
    container = get_container()
    # SPEC-ARO-043: no live Keycloak exists in this test environment (keycloak_token_url
    # defaults to "disabled") — swapped for a fake here so this test can exercise the
    # real triage call's own HTTP shape without also standing up a mock Keycloak.
    container.ticket_workflow_client._token_provider = _FakeServiceTokenProvider()
    conversation_id = _start_conversation(escalation_client, container, "employee-7", "conv-msg-3")

    response = escalation_client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        json={"text": "my laptop screen is broken and won't turn on"},
        headers={"Authorization": f"Bearer {_fake_jwt('employee-7')}", "Idempotency-Key": "msg-3"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["type"] == "escalation"
    assert body["display_id"] == "INC-9000"
    assert body["assigned_team"] == "IT Support"

    workflow_after = escalation_client.get(f"/internal/agent-runtime/v1/workflows/{conversation_id}")
    assert workflow_after.json()["state"] == "COMPLETED"


def test_send_message_without_escalation_routing_configured_is_a_bad_gateway(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-8", "conv-msg-4")

    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        json={"text": "my laptop screen is broken and won't turn on"},
        headers={"Authorization": f"Bearer {_fake_jwt('employee-8')}", "Idempotency-Key": "msg-4"},
    )

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "ESCALATION_ROUTING_NOT_CONFIGURED"


def test_send_message_from_a_different_employee_is_forbidden(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-9", "conv-msg-5")

    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages",
        json={"text": "hello"},
        headers={"Authorization": f"Bearer {_fake_jwt('someone-else')}", "Idempotency-Key": "msg-5"},
    )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONVERSATION_ACCESS_DENIED"


def test_send_message_against_an_unknown_conversation_is_a_404(client: TestClient) -> None:
    response = client.post(
        f"/api/v1/conversations/{uuid.uuid4()}/messages",
        json={"text": "hello"},
        headers={"Authorization": f"Bearer {_fake_jwt('employee-10')}", "Idempotency-Key": "msg-6"},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "CONVERSATION_NOT_FOUND"


def test_get_conversation_returns_its_real_state_for_its_own_owner(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-11", "conv-get-1")

    response = client.get(f"/api/v1/conversations/{conversation_id}", headers={"Authorization": f"Bearer {_fake_jwt('employee-11')}"})

    assert response.status_code == 200
    body = response.json()
    assert body["conversation_id"] == conversation_id
    assert body["state"] == "RUNNING"


def test_get_conversation_denies_a_different_employee(client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(client, container, "employee-12", "conv-get-2")

    response = client.get(f"/api/v1/conversations/{conversation_id}", headers={"Authorization": f"Bearer {_fake_jwt('someone-else')}"})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONVERSATION_ACCESS_DENIED"


def test_get_conversation_for_an_unknown_id_is_a_404(client: TestClient) -> None:
    response = client.get(f"/api/v1/conversations/{uuid.uuid4()}", headers={"Authorization": f"Bearer {_fake_jwt('employee-13')}"})

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "CONVERSATION_NOT_FOUND"


def test_get_most_recent_conversation_returns_the_newest_one_for_that_employee(client: TestClient) -> None:
    container = get_container()
    _start_conversation(client, container, "employee-14", "conv-get-3")
    second_id = _start_conversation(client, container, "employee-14", "conv-get-4")

    response = client.get("/api/v1/conversations/most-recent", headers={"Authorization": f"Bearer {_fake_jwt('employee-14')}"})

    assert response.status_code == 200
    assert response.json()["conversation_id"] == second_id


def test_get_most_recent_conversation_for_an_employee_with_none_is_a_404(client: TestClient) -> None:
    response = client.get(
        "/api/v1/conversations/most-recent", headers={"Authorization": f"Bearer {_fake_jwt('employee-with-no-conversations')}"},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "CONVERSATION_NOT_FOUND"


@pytest.fixture
def confirmation_client(monkeypatch: pytest.MonkeyPatch):
    """SPEC-ARO-040: a fast bounded-wait window (Settings' own real defaults are 2s/0.1s —
    load-tested defaults this environment has nothing real to load-test against yet) so
    these tests don't pay a real multi-second wait for the always-honest
    "still-processing" timeout.
    """
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(
        agent_runtime_persistence="memory", confirm_bounded_wait_timeout_seconds=0.05,
        confirm_bounded_wait_poll_interval_seconds=0.01,
    ))
    get_container.cache_clear()
    return TestClient(create_app())


def _send_message(client: TestClient, conversation_id: str, sub: str, text: str, idempotency_key: str) -> dict:
    response = client.post(
        f"/api/v1/conversations/{conversation_id}/messages", json={"text": text},
        headers={"Authorization": f"Bearer {_fake_jwt(sub)}", "Idempotency-Key": idempotency_key},
    )
    assert response.status_code == 200
    return response.json()


def test_confirm_action_dispatches_a_real_tool_request_and_honestly_reports_still_processing(confirmation_client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(confirmation_client, container, "employee-15", "conv-confirm-1")
    body = _send_message(confirmation_client, conversation_id, "employee-15", "I need to reset my password", "msg-confirm-1")
    assert body["type"] == "proposedAction"
    action_id = body["action_id"]

    response = confirmation_client.post(
        f"/api/v1/conversations/{conversation_id}/actions/{action_id}/confirm",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-15')}", "Idempotency-Key": "confirm-app-1"},
    )

    assert response.status_code == 200
    assert response.json()["outcome"] == "still-processing"


class _FakeServiceTokenProvider:
    def get_token(self) -> str:
        return "service-identity-token"


def test_confirm_action_with_a_high_risk_proposal_creates_a_real_governance_approval_request(confirmation_client: TestClient) -> None:
    container = get_container()
    container.governance_approval_client._token_provider = _FakeServiceTokenProvider()

    def approval_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(201, json={
            "approvalRequestId": "approval-app-1", "requestKey": "k", "sourceDomain": "agent-runtime", "sourceRequestId": "s",
            "status": "REQUESTED", "approvalType": "TOOL_EXECUTION", "riskLevel": "HIGH", "constraints": [],
            "createdAt": "2026-09-01T00:00:00Z", "updatedAt": "2026-09-01T00:00:00Z",
        })

    container.governance_approval_client._http_client = httpx.Client(transport=httpx.MockTransport(approval_handler))
    container.conversation_reasoning_port.decide = lambda message_text, knowledge_snippets, attachments=None: ReasoningOutcome(
        kind="proposed_action", action_summary="Delete production data", action_risk_level="HIGH",
    )

    conversation_id = _start_conversation(confirmation_client, container, "employee-16", "conv-confirm-2")
    body = _send_message(confirmation_client, conversation_id, "employee-16", "please clean up", "msg-confirm-2")
    assert body["type"] == "proposedAction"
    action_id = body["action_id"]

    response = confirmation_client.post(
        f"/api/v1/conversations/{conversation_id}/actions/{action_id}/confirm",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-16')}", "Idempotency-Key": "confirm-app-2"},
    )

    assert response.status_code == 200
    assert response.json()["outcome"] == "awaiting-approval"

    workflow_after = confirmation_client.get(f"/internal/agent-runtime/v1/workflows/{conversation_id}")
    assert workflow_after.json()["state"] == "WAITING_FOR_APPROVAL"


def test_decline_action_has_zero_side_effects_over_http(confirmation_client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(confirmation_client, container, "employee-17", "conv-decline-1")
    body = _send_message(confirmation_client, conversation_id, "employee-17", "I need to reset my password", "msg-decline-1")
    action_id = body["action_id"]

    response = confirmation_client.post(
        f"/api/v1/conversations/{conversation_id}/actions/{action_id}/decline",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-17')}", "Idempotency-Key": "decline-app-1"},
    )

    assert response.status_code == 200
    assert response.json()["outcome"] == "declined"


def test_confirm_action_from_a_different_employee_is_forbidden(confirmation_client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(confirmation_client, container, "employee-18", "conv-confirm-3")
    body = _send_message(confirmation_client, conversation_id, "employee-18", "I need to reset my password", "msg-confirm-3")
    action_id = body["action_id"]

    response = confirmation_client.post(
        f"/api/v1/conversations/{conversation_id}/actions/{action_id}/confirm",
        headers={"Authorization": f"Bearer {_fake_jwt('someone-else')}", "Idempotency-Key": "confirm-app-3"},
    )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONVERSATION_ACCESS_DENIED"


def test_confirm_action_against_an_unknown_action_id_is_a_404(confirmation_client: TestClient) -> None:
    container = get_container()
    conversation_id = _start_conversation(confirmation_client, container, "employee-19", "conv-confirm-4")

    response = confirmation_client.post(
        f"/api/v1/conversations/{conversation_id}/actions/{uuid.uuid4()}/confirm",
        headers={"Authorization": f"Bearer {_fake_jwt('employee-19')}", "Idempotency-Key": "confirm-app-4"},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "ACTION_NOT_FOUND"
