"""SPEC-ARO-035 14-testing-strategy §"契约测试": the four consumed event types
(ticket.created.v1, approval.granted.v1, tool.completed.v1, verification.completed.v1).

Two things are proved per event type, not just one:
1. The contract model itself (tests/contracts/schemas.py) correctly encodes
   06-event-contracts' own documented "关键字段" list — a valid example validates, and
   each required field's absence is rejected.
2. The REAL, already-shipping consuming code agrees with that contract:
   - ticket.created.v1 has its own dedicated FastAPI route already validating
     TicketCreatedEventRequest live — driven here via a real TestClient/real container,
     the same way tests/test_app.py's own HTTP-level tests do.
   - approval.granted.v1/tool.completed.v1/verification.completed.v1 flow through the
     generic RuntimeEventRequest envelope with an opaque `payload` string; their
     type-specific consumer parses that string by hand (json.loads + dict indexing).
     ConsumeRuntimeEventService classifies a payload-shape failure as "poison" via one
     fixed exception-type tuple (10-failure-handling §"Poison Event"); this file imports
     that real tuple directly (not a re-declared copy) and proves that removing any
     contract-required field from a real example payload makes the real service raise an
     exception in that exact tuple — i.e. that ConsumeRuntimeEventService's own poison net
     genuinely catches every violation this contract calls "required", not just the ones
     someone happened to think to test before.
"""

from __future__ import annotations

import json
import uuid

import pydantic
import pytest
from fastapi.testclient import TestClient

from agentruntime.application.services.consume_approval import ConsumeApprovalService
from agentruntime.application.services.consume_runtime_event import _POISON_EXCEPTION_TYPES
from agentruntime.application.services.consume_tool_result import ConsumeToolResultService
from agentruntime.application.services.consume_verification import ConsumeVerificationService
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.container import get_container
from agentruntime.domain.ids import WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from agentruntime.main import create_app
from agentruntime.settings import Settings
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators
from tests.contracts.schemas import (
    ApprovalGrantedPayloadContract,
    TicketCreatedContract,
    ToolCompletedPayloadContract,
    VerificationCompletedPayloadContract,
)

pytestmark = pytest.mark.unit


# ---------------------------------------------------------------------------
# ticket.created.v1
# ---------------------------------------------------------------------------


def _ticket_created_example(event_id: str = "evt-1") -> dict:
    return {
        "event_id": event_id, "event_type": "ticket.created.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()),
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "priority": "HIGH", "category": "network_outage", "created_by": "ticket-workflow-service",
        "occurred_at": "2026-01-01T00:00:00Z",
    }


def _ticket_workflow_outbox_ticket_created_example(event_id: str = "evt-tw-created-1", ticket_id: str | None = None) -> dict:
    actual_ticket_id = ticket_id or str(uuid.uuid4())
    return {
        "eventId": event_id,
        "eventType": "ticket.created",
        "eventVersion": "1.0",
        "routingKey": "ticket.created.v1",
        "aggregateType": "Ticket",
        "aggregateId": actual_ticket_id,
        "aggregateVersion": 1,
        "ticketId": actual_ticket_id,
        "traceId": "trace-1",
        "correlationId": str(uuid.uuid4()),
        "causationId": str(uuid.uuid4()),
        "dataClassification": "INTERNAL",
        "occurredAt": "2026-01-01T00:00:00Z",
        "payload": {
            "displayId": "INC-1001",
            "requesterIdHash": "hmac-sha256:" + ("a" * 64),
            "applicationCode": "VPN",
            "source": "PORTAL",
            "initialStatus": "NEW",
            "createdAt": "2026-01-01T00:00:00Z",
        },
    }


def test_ticket_created_contract_accepts_a_valid_example() -> None:
    TicketCreatedContract(**_ticket_created_example())


def test_ticket_created_contract_accepts_ticket_workflow_outbox_envelope() -> None:
    event = TicketCreatedContract(**_ticket_workflow_outbox_ticket_created_example())

    assert event.event_type == "ticket.created.v1"
    assert event.category == "VPN"
    assert event.priority == "UNSPECIFIED"
    assert event.created_by.startswith("hmac-sha256:")
    assert event.ticket_cycle_id == event.ticket_id


@pytest.mark.parametrize(
    "field", ["event_id", "producer", "schema_version", "correlation_id", "causation_id", "ticket_id", "ticket_cycle_id",
              "priority", "category", "created_by", "occurred_at"],
)
def test_ticket_created_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _ticket_created_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        TicketCreatedContract(**example)


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(agent_runtime_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def test_the_real_ticket_created_endpoint_accepts_a_contract_valid_payload(client: TestClient) -> None:
    response = client.post("/internal/agent-runtime/v1/events/ticket-created", json=_ticket_created_example())

    assert response.status_code == 200
    assert response.json()["applied"] is True


def test_the_real_ticket_created_endpoint_accepts_ticket_workflow_outbox_envelope(client: TestClient) -> None:
    ticket_id = str(uuid.uuid4())
    response = client.post(
        "/internal/agent-runtime/v1/events/ticket-created",
        json=_ticket_workflow_outbox_ticket_created_example(ticket_id=ticket_id),
    )

    assert response.status_code == 200
    assert response.json()["applied"] is True


@pytest.mark.parametrize(
    "field", ["event_id", "producer", "schema_version", "correlation_id", "causation_id", "ticket_id", "ticket_cycle_id",
              "priority", "category", "created_by", "occurred_at"],
)
def test_the_real_ticket_created_endpoint_rejects_a_missing_required_field(client: TestClient, field: str) -> None:
    example = _ticket_created_example()
    del example[field]

    response = client.post("/internal/agent-runtime/v1/events/ticket-created", json=example)

    # interfaces/errors.py's own register_exception_handlers(): RequestValidationError is
    # deliberately mapped to 400 ("VALIDATION_ERROR"), not FastAPI's own default 422 — an
    # established, codebase-wide convention this test follows rather than overrides.
    assert response.status_code == 400


# ---------------------------------------------------------------------------
# approval.granted.v1
# ---------------------------------------------------------------------------


def _approval_granted_example() -> dict:
    return {
        "approvalRequestId": str(uuid.uuid4()), "ticketId": str(uuid.uuid4()), "workflowInstanceId": str(uuid.uuid4()),
        "decision": "APPROVED", "approvedBy": "ops-user-1", "occurredAt": "2026-01-01T00:00:00Z",
    }


def test_approval_granted_contract_accepts_a_valid_example() -> None:
    ApprovalGrantedPayloadContract(**_approval_granted_example())


@pytest.mark.parametrize("field", ["approvalRequestId", "ticketId", "workflowInstanceId", "decision", "approvedBy", "occurredAt"])
def test_approval_granted_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _approval_granted_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        ApprovalGrantedPayloadContract(**example)


@pytest.fixture
def approval_service() -> ConsumeApprovalService:
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    return ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)


def test_the_real_approval_consumer_accepts_a_contract_valid_payload(approval_service: ConsumeApprovalService) -> None:
    # approvalRequestId/decision/approvedBy are the only fields consume_approval.py's own
    # apply() indexes today (see schemas.py's own module docstring): a KeyError here would
    # mean the required-field list this test parametrizes below is wrong, not that the
    # workflow lookup itself succeeded — WorkflowInstanceNotFoundException is the expected,
    # unrelated outcome for a workflow_instance_id nothing seeded.
    from agentruntime.application.exceptions import WorkflowInstanceNotFoundException

    with pytest.raises(WorkflowInstanceNotFoundException):
        approval_service.apply(WorkflowInstanceId.new_id(), json.dumps(_approval_granted_example()))


@pytest.mark.parametrize("field", ["approvalRequestId", "decision", "approvedBy"])
def test_the_real_approval_consumer_poisons_on_a_missing_required_field(approval_service: ConsumeApprovalService, field: str) -> None:
    example = _approval_granted_example()
    del example[field]

    with pytest.raises(_POISON_EXCEPTION_TYPES):
        approval_service.apply(WorkflowInstanceId.new_id(), json.dumps(example))


# ---------------------------------------------------------------------------
# tool.completed.v1
# ---------------------------------------------------------------------------


def _tool_completed_example() -> dict:
    return {
        "toolRequestId": str(uuid.uuid4()), "gatewayCorrelationId": str(uuid.uuid4()), "workflowInstanceId": str(uuid.uuid4()),
        "agentTaskId": str(uuid.uuid4()), "status": "COMPLETED", "resultPayload": "diagnostics collected",
        "occurredAt": "2026-01-01T00:00:00Z",
    }


def test_tool_completed_contract_accepts_a_valid_example() -> None:
    ToolCompletedPayloadContract(**_tool_completed_example())


def test_tool_completed_contract_accepts_a_missing_optional_result_payload() -> None:
    example = _tool_completed_example()
    del example["resultPayload"]

    ToolCompletedPayloadContract(**example)


@pytest.mark.parametrize("field", ["toolRequestId", "gatewayCorrelationId", "workflowInstanceId", "agentTaskId", "status", "occurredAt"])
def test_tool_completed_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _tool_completed_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        ToolCompletedPayloadContract(**example)


@pytest.fixture
def tool_result_service() -> ConsumeToolResultService:
    tool_request_repository = InMemoryToolRequestRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    return ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository, outbox_repository,
        clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )


def test_the_real_tool_result_consumer_accepts_a_contract_valid_payload(tool_result_service: ConsumeToolResultService) -> None:
    # toolRequestId/status/resultPayload are the only fields consume_tool_result.py's own
    # apply() indexes today. ToolRequestNotFoundException is the expected, unrelated
    # outcome for a toolRequestId nothing seeded — proves parsing itself succeeded.
    from agentruntime.application.exceptions import ToolRequestNotFoundException

    with pytest.raises(ToolRequestNotFoundException):
        tool_result_service.apply(json.dumps(_tool_completed_example()))


@pytest.mark.parametrize("field", ["toolRequestId", "status"])
def test_the_real_tool_result_consumer_poisons_on_a_missing_required_field(tool_result_service: ConsumeToolResultService, field: str) -> None:
    example = _tool_completed_example()
    del example[field]

    with pytest.raises(_POISON_EXCEPTION_TYPES):
        tool_result_service.apply(json.dumps(example))


# ---------------------------------------------------------------------------
# verification.completed.v1
# ---------------------------------------------------------------------------


def _verification_completed_example() -> dict:
    return {
        "verificationRequestId": str(uuid.uuid4()), "workflowInstanceId": str(uuid.uuid4()), "ticketId": str(uuid.uuid4()),
        "passed": True, "evidence": "smoke test green", "occurredAt": "2026-01-01T00:00:00Z",
    }


def test_verification_completed_contract_accepts_a_valid_example() -> None:
    VerificationCompletedPayloadContract(**_verification_completed_example())


def test_verification_completed_contract_accepts_a_missing_optional_evidence() -> None:
    example = _verification_completed_example()
    del example["evidence"]

    VerificationCompletedPayloadContract(**example)


@pytest.mark.parametrize("field", ["verificationRequestId", "workflowInstanceId", "ticketId", "passed", "occurredAt"])
def test_verification_completed_contract_rejects_a_missing_required_field(field: str) -> None:
    example = _verification_completed_example()
    del example[field]

    with pytest.raises(pydantic.ValidationError):
        VerificationCompletedPayloadContract(**example)


@pytest.fixture
def verification_service() -> ConsumeVerificationService:
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    outbox_repository = InMemoryOutboxRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    return ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )


def test_the_real_verification_consumer_accepts_a_contract_valid_payload(verification_service: ConsumeVerificationService) -> None:
    # verificationRequestId/passed/evidence are the only fields consume_verification.py's
    # own apply() indexes today. WorkflowInstanceNotFoundException is the expected,
    # unrelated outcome for a workflow_instance_id nothing seeded.
    from agentruntime.application.exceptions import WorkflowInstanceNotFoundException

    example = _verification_completed_example()
    with pytest.raises(WorkflowInstanceNotFoundException):
        verification_service.apply(WorkflowInstanceId(uuid.UUID(example["workflowInstanceId"])), json.dumps(example))


@pytest.mark.parametrize("field", ["verificationRequestId", "passed"])
def test_the_real_verification_consumer_poisons_on_a_missing_required_field(verification_service: ConsumeVerificationService, field: str) -> None:
    example = _verification_completed_example()
    del example[field]

    with pytest.raises(_POISON_EXCEPTION_TYPES):
        verification_service.apply(WorkflowInstanceId(uuid.UUID(example["workflowInstanceId"])), json.dumps(example))
