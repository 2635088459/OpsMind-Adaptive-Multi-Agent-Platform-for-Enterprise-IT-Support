from __future__ import annotations

import dataclasses
import json
import uuid
from datetime import UTC, datetime

import pytest

from agentruntime.application.commands import RuntimeEventEnvelope
from agentruntime.application.exceptions import (
    PoisonRuntimeEventException,
    StaleRuntimeEventException,
    ToolRequestNotFoundException,
    WorkflowInstanceNotFoundException,
)
from agentruntime.application.records import AgentTaskRecord, ToolRequestRecord, WorkflowInstanceRecord
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.consume_approval import ConsumeApprovalService
from agentruntime.application.services.consume_runtime_event import CONSUMER_NAME, ConsumeRuntimeEventService
from agentruntime.application.services.consume_tool_result import ConsumeToolResultService
from agentruntime.application.services.consume_verification import ConsumeVerificationService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain.enums import AgentTaskState, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CausationId,
    CheckpointId,
    CorrelationId,
    DefinitionVersion,
    LeaseToken,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryPoisonEventRepository,
    InMemoryProcessedEventRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    consume_tool_result_service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )
    consume_approval_service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)
    consume_verification_service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service
    )
    service = ConsumeRuntimeEventService(
        processed_event_repository, workflow_instance_repository, clock, consume_tool_result_service, consume_approval_service,
        consume_verification_service, InMemoryPoisonEventRepository(), telemetry, audit_recorder,
    )

    workflow_instance_id = WorkflowInstanceId.new_id()
    ticket_id = TicketId(uuid.uuid4())
    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, processed_event_repository, workflow_instance_id, ticket_id


def _envelope(workflow_instance_id: WorkflowInstanceId, ticket_id: TicketId, event_id: str, expected_workflow_version: int | None) -> RuntimeEventEnvelope:
    return RuntimeEventEnvelope(
        event_id=event_id, event_type="tool.completed", producer="tool-gateway-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC), payload="{}",
        expected_workflow_version=expected_workflow_version,
    )


def test_consuming_a_new_matching_event_marks_it_processed(wiring) -> None:
    service, processed_event_repository, workflow_instance_id, ticket_id = wiring

    applied = service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    assert applied is True
    assert processed_event_repository.is_processed("evt-1", CONSUMER_NAME) is True


def test_consuming_the_same_event_twice_is_a_no_op_the_second_time(wiring) -> None:
    service, _, workflow_instance_id, ticket_id = wiring
    service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    second_applied = service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    assert second_applied is False


def test_a_stale_event_is_rejected_but_still_marked_processed(wiring) -> None:
    service, processed_event_repository, workflow_instance_id, ticket_id = wiring

    with pytest.raises(StaleRuntimeEventException):
        service.consume(_envelope(workflow_instance_id, ticket_id, "evt-2", 99))
    assert processed_event_repository.is_processed("evt-2", CONSUMER_NAME) is True


def test_an_event_for_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service, _, _, ticket_id = wiring

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.consume(_envelope(WorkflowInstanceId.new_id(), ticket_id, "evt-3", None))


def test_marking_an_event_processed_here_does_not_affect_a_different_consumers_dedup_record(wiring) -> None:
    """SPEC-ARO-013 09-concurrency-and-idempotency §"消费事件幂等": dedup is keyed by
    (event_id, consumer_name) — two distinct logical consumers processing an event with
    the same event_id (e.g. a coincidental collision with ConsumeTicketCreatedService's own
    event stream) must not be treated as the same processed record.
    """
    service, processed_event_repository, workflow_instance_id, ticket_id = wiring

    service.consume(_envelope(workflow_instance_id, ticket_id, "evt-1", 1))

    assert processed_event_repository.is_processed("evt-1", CONSUMER_NAME) is True
    assert processed_event_repository.is_processed("evt-1", "some_other_consumer") is False


def test_a_tool_completed_v1_event_is_dispatched_to_consume_tool_result_service() -> None:
    """SPEC-ARO-020: the other 5 tests above deliberately use event_type="tool.completed"
    (no ".v1") to exercise the generic dedup/staleness gate in isolation, without ever
    tripping the type-specific dispatch. This test proves the dispatch itself actually
    fires for the real "tool.completed.v1" event_type — a bare envelope with no matching
    Tool Request would raise inside ConsumeToolResultService, so a *successful* consume()
    call here is only possible if the dispatch genuinely reached it.
    """
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    consume_tool_result_service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )
    consume_approval_service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)
    consume_verification_service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service
    )
    service = ConsumeRuntimeEventService(
        processed_event_repository, workflow_instance_repository, clock, consume_tool_result_service, consume_approval_service,
        consume_verification_service, InMemoryPoisonEventRepository(), telemetry, audit_recorder,
    )

    now = clock.now()
    ticket_id = TicketId(uuid.uuid4())
    workflow_instance_id = WorkflowInstanceId.new_id()
    running_workflow = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running_workflow)
    workflow_instance_repository.save(dataclasses.replace(running_workflow, state=WorkflowState.WAITING_FOR_TOOL, workflow_version=2))

    agent_task_id = AgentTaskId.new_id()
    claimed_task = AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.CLAIMED, task_version=1, worker_id="worker-1",
        lease_token=LeaseToken.new_token(), lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    )
    agent_task_repository.save(claimed_task)
    agent_task_repository.save(dataclasses.replace(claimed_task, state=AgentTaskState.WAITING_TOOL, task_version=2))

    tool_request_id = ToolRequestId.new_id()
    tool_request_repository.save(ToolRequestRecord(
        id=tool_request_id, workflow_instance_id=workflow_instance_id, agent_task_id=agent_task_id,
        preceding_checkpoint_id=CheckpointId.new_id(), tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.DISPATCHED, created_at=now, updated_at=now,
    ))

    envelope = RuntimeEventEnvelope(
        event_id="evt-tool-1", event_type="tool.completed.v1", producer="tool-gateway-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
        payload=json.dumps({"toolRequestId": str(tool_request_id), "status": "COMPLETED", "resultPayload": "diagnostics ok"}),
        expected_workflow_version=2,
    )

    applied = service.consume(envelope)

    assert applied is True
    task = agent_task_repository.find_by_id(agent_task_id)
    assert task.state is AgentTaskState.COMPLETED
    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.RUNNING


def test_an_approval_granted_v1_event_is_dispatched_to_consume_approval_service() -> None:
    """SPEC-ARO-021: mirrors the tool.completed.v1 dispatch-proof test above — the
    original 5 tests deliberately use event_type="tool.completed" and never trip either
    type-specific dispatch branch.
    """
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    consume_tool_result_service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )
    consume_approval_service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)
    consume_verification_service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service
    )
    service = ConsumeRuntimeEventService(
        processed_event_repository, workflow_instance_repository, clock, consume_tool_result_service, consume_approval_service,
        consume_verification_service, InMemoryPoisonEventRepository(), telemetry, audit_recorder,
    )

    now = clock.now()
    ticket_id = TicketId(uuid.uuid4())
    workflow_instance_id = WorkflowInstanceId.new_id()
    running_workflow = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running_workflow)
    workflow_instance_repository.save(dataclasses.replace(running_workflow, state=WorkflowState.WAITING_FOR_APPROVAL, workflow_version=2))

    envelope = RuntimeEventEnvelope(
        event_id="evt-approval-1", event_type="approval.granted.v1", producer="approval-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
        payload=json.dumps({"approvalRequestId": str(uuid.uuid4()), "decision": "APPROVED", "approvedBy": "ops-user-1"}),
        expected_workflow_version=2,
    )

    applied = service.consume(envelope)

    assert applied is True
    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.RUNNING


def test_a_verification_completed_v1_event_is_dispatched_to_consume_verification_service() -> None:
    """SPEC-ARO-022: mirrors the two dispatch-proof tests above."""
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    consume_tool_result_service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )
    consume_approval_service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)
    consume_verification_service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service
    )
    service = ConsumeRuntimeEventService(
        processed_event_repository, workflow_instance_repository, clock, consume_tool_result_service, consume_approval_service,
        consume_verification_service, InMemoryPoisonEventRepository(), telemetry, audit_recorder,
    )

    now = clock.now()
    ticket_id = TicketId(uuid.uuid4())
    workflow_instance_id = WorkflowInstanceId.new_id()
    running_workflow = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(running_workflow)
    workflow_instance_repository.save(dataclasses.replace(running_workflow, state=WorkflowState.WAITING_FOR_VERIFICATION, workflow_version=2))

    envelope = RuntimeEventEnvelope(
        event_id="evt-verification-1", event_type="verification.completed.v1", producer="verification-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC),
        payload=json.dumps({"verificationRequestId": str(uuid.uuid4()), "passed": True, "evidence": "all checks green"}),
        expected_workflow_version=2,
    )

    applied = service.consume(envelope)

    assert applied is True
    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.RUNNING


def _full_wiring():
    """SPEC-ARO-024: shared construction for the poison-event tests below — mirrors the
    dispatch-proof tests above, but returns the extra repositories (tool_request,
    poison_event) those tests didn't need.
    """
    processed_event_repository = InMemoryProcessedEventRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    poison_event_repository = InMemoryPoisonEventRepository()
    clock = FakeClock()
    coordinate_agent_tasks_service = CoordinateAgentTasksService(agent_task_repository, checkpoint_repository)
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    complete_workflow_service = CompleteWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock, checkpoint_repository,
        telemetry, audit_recorder,
    )
    consume_tool_result_service = ConsumeToolResultService(
        tool_request_repository, agent_task_repository, workflow_instance_repository, checkpoint_repository,
        outbox_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service,
    )
    consume_approval_service = ConsumeApprovalService(workflow_instance_repository, checkpoint_repository, clock, fail_workflow_service)
    consume_verification_service = ConsumeVerificationService(
        workflow_instance_repository, clock, coordinate_agent_tasks_service, complete_workflow_service, fail_workflow_service
    )
    service = ConsumeRuntimeEventService(
        processed_event_repository, workflow_instance_repository, clock, consume_tool_result_service, consume_approval_service,
        consume_verification_service, poison_event_repository, telemetry, audit_recorder,
    )

    now = clock.now()
    ticket_id = TicketId(uuid.uuid4())
    workflow_instance_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=ticket_id, ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, processed_event_repository, poison_event_repository, workflow_instance_id, ticket_id


def _tool_completed_envelope(workflow_instance_id: WorkflowInstanceId, ticket_id: TicketId, event_id: str, payload: str) -> RuntimeEventEnvelope:
    return RuntimeEventEnvelope(
        event_id=event_id, event_type="tool.completed.v1", producer="tool-gateway-service", schema_version=1,
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), ticket_id=ticket_id,
        workflow_instance_id=workflow_instance_id, occurred_at=datetime(2026, 1, 1, tzinfo=UTC), payload=payload,
        expected_workflow_version=None,
    )


def test_malformed_json_payload_is_classified_as_a_poison_event() -> None:
    """SPEC-ARO-024 10-failure-handling §"Poison Event": the "invalid" leg of Duplicate/
    Stale/Invalid classification — a payload that cannot even be parsed as JSON.
    """
    service, processed_event_repository, poison_event_repository, workflow_instance_id, ticket_id = _full_wiring()
    envelope = _tool_completed_envelope(workflow_instance_id, ticket_id, "evt-poison-1", "{not valid json")

    with pytest.raises(PoisonRuntimeEventException):
        service.consume(envelope)

    assert processed_event_repository.is_processed("evt-poison-1", CONSUMER_NAME) is False
    poisoned = poison_event_repository.find_all(10)
    assert len(poisoned) == 1
    assert poisoned[0].event_id == "evt-poison-1"
    assert poisoned[0].consumer_name == CONSUMER_NAME
    assert poisoned[0].payload == "{not valid json"


def test_a_payload_missing_a_required_field_is_classified_as_a_poison_event() -> None:
    service, _processed, poison_event_repository, workflow_instance_id, ticket_id = _full_wiring()
    envelope = _tool_completed_envelope(workflow_instance_id, ticket_id, "evt-poison-2", json.dumps({"status": "COMPLETED"}))

    with pytest.raises(PoisonRuntimeEventException):
        service.consume(envelope)

    poisoned = poison_event_repository.find_all(10)
    assert len(poisoned) == 1
    assert "toolRequestId" in poisoned[0].error_message


def test_a_poisoned_event_can_be_replayed_under_the_same_event_id_once_fixed() -> None:
    """The whole point of NOT mark_processed-ing a poison event: a corrected redelivery
    under the same event_id must not be treated as an already-processed duplicate.
    """
    service, _processed, poison_event_repository, workflow_instance_id, ticket_id = _full_wiring()
    with pytest.raises(PoisonRuntimeEventException):
        service.consume(_tool_completed_envelope(workflow_instance_id, ticket_id, "evt-poison-3", "{not valid json"))

    with pytest.raises(ToolRequestNotFoundException):
        # Same event_id, now valid JSON but referencing a real-shaped, nonexistent Tool
        # Request — proves the replay actually reached the type-specific consumer rather
        # than being silently dropped as a duplicate.
        service.consume(_tool_completed_envelope(
            workflow_instance_id, ticket_id, "evt-poison-3", json.dumps({"toolRequestId": str(uuid.uuid4()), "status": "COMPLETED"})
        ))

    # Only the first (poison) delivery was recorded — the second reached a different,
    # already-well-classified outcome.
    assert len(poison_event_repository.find_all(10)) == 1


def test_a_well_classified_business_rejection_is_still_marked_processed_not_poisoned() -> None:
    """A syntactically valid payload referencing a Tool Request that genuinely does not
    exist is a well-understood rejection (ToolRequestNotFoundException), not poison —
    the existing "must not be retried forever" behavior (SPEC-ARO-020) is unchanged.
    """
    service, processed_event_repository, poison_event_repository, workflow_instance_id, ticket_id = _full_wiring()
    envelope = _tool_completed_envelope(
        workflow_instance_id, ticket_id, "evt-not-poison-1", json.dumps({"toolRequestId": str(uuid.uuid4()), "status": "COMPLETED"})
    )

    with pytest.raises(ToolRequestNotFoundException):
        service.consume(envelope)

    assert processed_event_repository.is_processed("evt-not-poison-1", CONSUMER_NAME) is True
    assert poison_event_repository.find_all(10) == []
