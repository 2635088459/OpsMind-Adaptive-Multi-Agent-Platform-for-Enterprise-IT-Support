"""SPEC-MK-021 (02-ticket-workflow consumed contracts) / SPEC-MK-022
(03-agent-runtime-orchestration consumed contracts) 06-event-contracts: the four
consumed event types (ticket.resolved.v1, ticket.closed.v1, workflow.completed.v1,
workflow.failed.v1).

Two things are proved per event type, mirroring agent-runtime-service's own
tests/contracts/test_consumed_event_contracts.py:
1. The contract model itself (tests/contracts/schemas.py) correctly encodes
   06-event-contracts' own "关键字段" list — a valid example validates, and each
   required field's absence is rejected.
2. The REAL, already-shipping route agrees with that contract, under *both* wire
   shapes it accepts: a clean already-normalized body, and the upstream service's own
   real raw outbox envelope (camelCase, nested payload) — "02/03 remain system of
   record" (SPEC-MK-010/022's own domain-rule) means those real wire shapes must be
   accepted as-is, not only a simplified test fixture.
"""

from __future__ import annotations

import uuid

import pydantic
import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.main import create_app
from memoryknowledge.settings import Settings
from tests.contracts.schemas import (
    TicketClosedContract,
    TicketResolvedContract,
    WorkflowCompletedContract,
    WorkflowFailedContract,
)

pytestmark = pytest.mark.unit


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: Settings(memory_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


# ---------------------------------------------------------------------------
# ticket.resolved.v1
# ---------------------------------------------------------------------------


def _ticket_resolved_example(event_id: str = "evt-1") -> dict:
    return {
        "event_id": event_id, "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "resolution_code": "MFA_RESET_SUCCESSFUL", "resolution_summary": "reset device binding fixed the mfa loop",
        "resolved_by": "verification-agent", "resolved_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }


def _ticket_workflow_outbox_ticket_resolved_example(event_id: str = "evt-tw-resolved-1") -> dict:
    ticket_id = str(uuid.uuid4())
    return {
        "eventId": event_id, "eventType": "ticket.resolved", "eventVersion": "1.0", "routingKey": "ticket.resolved.v1",
        "aggregateType": "Ticket", "aggregateId": ticket_id, "aggregateVersion": 3, "ticketId": ticket_id,
        "traceId": "trace-1", "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()),
        "dataClassification": "INTERNAL", "createdAt": "2026-08-16T00:00:00Z", "availableAt": "2026-08-16T00:00:00Z",
        "payload": {
            "supportQueueId": "q-1", "assigneeId": "agent-1", "resolutionCycleId": str(uuid.uuid4()),
            "previousStatus": "IN_PROGRESS", "newStatus": "RESOLVED", "resolutionCode": "MFA_RESET_SUCCESSFUL",
            "resolutionSummary": "reset device binding fixed the mfa loop", "resolvedBy": "verification-agent",
            "resolvedAt": "2026-08-16T00:00:00Z", "autoCloseDueAt": "2026-08-19T00:00:00Z",
        },
    }


def test_ticket_resolved_contract_accepts_a_valid_example() -> None:
    TicketResolvedContract(**_ticket_resolved_example())


@pytest.mark.parametrize(
    "field", ["event_id", "ticket_id", "ticket_cycle_id", "resolution_code", "resolution_summary", "resolved_by", "resolved_at", "correlation_id"],
)
def test_ticket_resolved_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _ticket_resolved_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        TicketResolvedContract(**example)


def test_the_real_ticket_resolved_endpoint_accepts_a_contract_valid_payload(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/ticket-resolved", json=_ticket_resolved_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


def test_the_real_ticket_resolved_endpoint_accepts_the_ticket_workflow_outbox_envelope(client: TestClient) -> None:
    response = client.post(
        "/internal/memory/v1/events/ticket-resolved", json=_ticket_workflow_outbox_ticket_resolved_example(),
    )

    assert response.status_code == 200
    assert response.json()["applied"] is True


# ---------------------------------------------------------------------------
# ticket.closed.v1
# ---------------------------------------------------------------------------


def _ticket_closed_example(event_id: str = "evt-2") -> dict:
    return {
        "event_id": event_id, "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "close_reason_code": "REQUESTER_CONFIRMED", "close_reason": "requester confirmed the fix resolved the issue",
        "closed_by": "employee-1", "closed_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }


def _ticket_workflow_outbox_ticket_closed_example(event_id: str = "evt-tw-closed-1") -> dict:
    ticket_id = str(uuid.uuid4())
    return {
        "eventId": event_id, "eventType": "ticket.closed", "eventVersion": "1.0", "routingKey": "ticket.closed.v1",
        "aggregateType": "Ticket", "aggregateId": ticket_id, "aggregateVersion": 4, "ticketId": ticket_id,
        "traceId": "trace-2", "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()),
        "dataClassification": "INTERNAL", "createdAt": "2026-08-16T00:00:00Z", "availableAt": "2026-08-16T00:00:00Z",
        "payload": {
            "supportQueueId": "q-1", "assigneeId": "agent-1", "resolutionCycleId": str(uuid.uuid4()),
            "previousStatus": "RESOLVED", "newStatus": "CLOSED", "closeReasonCode": "REQUESTER_CONFIRMED",
            "closedBy": "employee-1", "closedAt": "2026-08-16T00:00:00Z",
        },
    }


def test_ticket_closed_contract_accepts_a_valid_example() -> None:
    TicketClosedContract(**_ticket_closed_example())


@pytest.mark.parametrize(
    "field", ["event_id", "ticket_id", "ticket_cycle_id", "close_reason_code", "close_reason", "closed_by", "closed_at", "correlation_id"],
)
def test_ticket_closed_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _ticket_closed_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        TicketClosedContract(**example)


def test_the_real_ticket_closed_endpoint_accepts_a_contract_valid_payload(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/ticket-closed", json=_ticket_closed_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


def test_the_real_ticket_closed_endpoint_accepts_the_ticket_workflow_outbox_envelope(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/ticket-closed", json=_ticket_workflow_outbox_ticket_closed_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


# ---------------------------------------------------------------------------
# workflow.completed.v1
# ---------------------------------------------------------------------------


def _workflow_completed_example(event_id: str = "evt-3") -> dict:
    return {
        "event_id": event_id, "workflow_instance_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "from_state": "IN_PROGRESS", "to_state": "COMPLETED", "workflow_version": 3,
        "occurred_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }


def _agent_runtime_outbox_workflow_completed_example(event_id: str = "evt-aro-completed-1") -> dict:
    workflow_instance_id = str(uuid.uuid4())
    return {
        "eventId": event_id, "eventType": "workflow.completed.v1", "aggregateId": workflow_instance_id,
        "ticketId": str(uuid.uuid4()), "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()),
        "occurredAt": "2026-08-16T00:00:00Z",
        "payload": {
            "workflowInstanceId": workflow_instance_id, "fromState": "IN_PROGRESS", "toState": "COMPLETED",
            "workflowVersion": 3, "occurredAt": "2026-08-16T00:00:00Z",
        },
    }


def test_workflow_completed_contract_accepts_a_valid_example() -> None:
    WorkflowCompletedContract(**_workflow_completed_example())


@pytest.mark.parametrize(
    "field", ["event_id", "workflow_instance_id", "ticket_id", "to_state", "workflow_version", "occurred_at", "correlation_id"],
)
def test_workflow_completed_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _workflow_completed_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        WorkflowCompletedContract(**example)


def test_the_real_workflow_completed_endpoint_accepts_a_contract_valid_payload(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/workflow-completed", json=_workflow_completed_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


def test_the_real_workflow_completed_endpoint_accepts_the_agent_runtime_outbox_envelope(client: TestClient) -> None:
    response = client.post(
        "/internal/memory/v1/events/workflow-completed", json=_agent_runtime_outbox_workflow_completed_example(),
    )

    assert response.status_code == 200
    assert response.json()["applied"] is True


# ---------------------------------------------------------------------------
# workflow.failed.v1
# ---------------------------------------------------------------------------


def _workflow_failed_example(event_id: str = "evt-4") -> dict:
    return {
        "event_id": event_id, "workflow_instance_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "from_state": "IN_PROGRESS", "to_state": "FAILED", "workflow_version": 2, "failure_reason": "tool gateway timeout",
        "occurred_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }


def _agent_runtime_outbox_workflow_failed_example(event_id: str = "evt-aro-failed-1") -> dict:
    workflow_instance_id = str(uuid.uuid4())
    return {
        "eventId": event_id, "eventType": "workflow.failed.v1", "aggregateId": workflow_instance_id,
        "ticketId": str(uuid.uuid4()), "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()),
        "occurredAt": "2026-08-16T00:00:00Z",
        "payload": {
            "workflowInstanceId": workflow_instance_id, "fromState": "IN_PROGRESS", "toState": "FAILED",
            "workflowVersion": 2, "failureReason": "tool gateway timeout", "occurredAt": "2026-08-16T00:00:00Z",
        },
    }


def test_workflow_failed_contract_accepts_a_valid_example() -> None:
    WorkflowFailedContract(**_workflow_failed_example())


@pytest.mark.parametrize(
    "field", ["event_id", "workflow_instance_id", "ticket_id", "to_state", "workflow_version", "failure_reason", "occurred_at", "correlation_id"],
)
def test_workflow_failed_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _workflow_failed_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        WorkflowFailedContract(**example)


def test_the_real_workflow_failed_endpoint_accepts_a_contract_valid_payload(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/workflow-failed", json=_workflow_failed_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


def test_the_real_workflow_failed_endpoint_accepts_the_agent_runtime_outbox_envelope(client: TestClient) -> None:
    response = client.post("/internal/memory/v1/events/workflow-failed", json=_agent_runtime_outbox_workflow_failed_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True
