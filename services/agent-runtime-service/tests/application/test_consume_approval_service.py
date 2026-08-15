from __future__ import annotations

import dataclasses
import json
import uuid

import pytest

from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.consume_approval import ConsumeApprovalService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.ids import DefinitionVersion, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
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
    service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)

    now = clock.now()
    workflow_instance_id = WorkflowInstanceId.new_id()
    running = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running)
    # No spec has built an entry path into WAITING_FOR_APPROVAL yet — seeded directly,
    # one version at a time per the repository's own CAS (see
    # tests/application/test_request_tool_service.py's _start_workflow_instance).
    workflow_instance_repository.save(dataclasses.replace(running, state=WorkflowState.WAITING_FOR_APPROVAL, workflow_version=2))

    return service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository


def _payload(decision: str = "APPROVED", approval_request_id: str | None = None, approved_by: str = "ops-user-1") -> str:
    return json.dumps({
        "approvalRequestId": approval_request_id or str(uuid.uuid4()), "decision": decision, "approvedBy": approved_by,
    })


def test_an_approved_decision_wakes_the_workflow_to_running(wiring) -> None:
    service, workflow_instance_id, workflow_instance_repository, _checkpoint_repository, _outbox_repository = wiring

    service.apply(workflow_instance_id, _payload("APPROVED"))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.RUNNING
    assert workflow.workflow_version == 3


def test_an_approved_decision_writes_a_recovery_snapshot_checkpoint(wiring) -> None:
    service, workflow_instance_id, _workflow_instance_repository, checkpoint_repository, _outbox_repository = wiring

    service.apply(workflow_instance_id, _payload("APPROVED", approval_request_id="req-1", approved_by="ops-user-3"))

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert [c.type for c in checkpoints] == [CheckpointType.RECOVERY_SNAPSHOT]
    payload = json.loads(checkpoints[0].payload)
    assert payload["approvalRequestId"] == "req-1"
    assert payload["decision"] == "APPROVED"
    assert payload["approvedBy"] == "ops-user-3"


def test_a_non_approved_decision_fails_the_workflow_with_an_auditable_reason(wiring) -> None:
    service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, outbox_repository = wiring

    service.apply(workflow_instance_id, _payload("REJECTED", approval_request_id="req-2", approved_by="ops-user-4"))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.FAILED

    # No RECOVERY_SNAPSHOT checkpoint on the reject path — there is no planner context
    # left to recover once the workflow is ending, not continuing.
    assert checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id) == []

    assert any(record.event_type == "workflow.failed.v1" for record in outbox_repository.recorded())


def test_a_delivery_for_a_workflow_no_longer_waiting_for_approval_is_a_no_op(wiring) -> None:
    """UC-03 step 3's own precondition guard doubles as the idempotency check: there is
    no local ApprovalRequest record (unlike Tool Request) whose own terminal status
    could otherwise signal "already resolved".
    """
    service, workflow_instance_id, workflow_instance_repository, checkpoint_repository, _outbox_repository = wiring
    service.apply(workflow_instance_id, _payload("APPROVED"))
    woken = workflow_instance_repository.find_by_id(workflow_instance_id)

    service.apply(workflow_instance_id, _payload("APPROVED"))

    unchanged = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert unchanged.workflow_version == woken.workflow_version
    assert len(checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.apply(WorkflowInstanceId.new_id(), _payload("APPROVED"))
