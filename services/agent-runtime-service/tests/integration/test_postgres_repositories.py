from __future__ import annotations

import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import pytest

from agentruntime.application.commands import ClaimReadyAgentTasksCommand
from agentruntime.application.exceptions import AgentTaskVersionConflictException, WorkflowInstanceVersionConflictException
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    CommandIdempotencyRecord,
    OutboxRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.application.services.claim_agent_task import ClaimAgentTaskService
from agentruntime.domain.enums import AgentTaskState, CheckpointType, OutboxStatus, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CausationId,
    CheckpointId,
    CorrelationId,
    DefinitionVersion,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.clock import SystemClockAdapter
from agentruntime.infrastructure.persistence.postgres.repositories import (
    PostgresAgentTaskRepository,
    PostgresCheckpointRepository,
    PostgresCommandIdempotencyRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresToolRequestRepository,
    PostgresWorkflowInstanceRepository,
)

pytestmark = pytest.mark.integration

NOW = datetime(2026, 1, 1, tzinfo=UTC)


def _workflow_instance_record(**overrides) -> WorkflowInstanceRecord:
    defaults = dict(
        id=WorkflowInstanceId.new_id(), ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=NOW, updated_at=NOW,
    )
    defaults.update(overrides)
    return WorkflowInstanceRecord(**defaults)


def _agent_task_record(workflow_instance_id: WorkflowInstanceId, **overrides) -> AgentTaskRecord:
    defaults = dict(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="collect",
        task_type="collect_diagnostics", depends_on_task_keys=frozenset(), state=AgentTaskState.PENDING,
        task_version=1, worker_id=None, lease_token=None, lease_expires_at=None, result_payload=None,
        failure_reason=None, pause_generation=0, created_at=NOW, updated_at=NOW,
    )
    defaults.update(overrides)
    return AgentTaskRecord(**defaults)


def test_workflow_instance_round_trips_through_postgres(session_factory) -> None:
    repo = PostgresWorkflowInstanceRepository(session_factory)
    record = _workflow_instance_record()

    saved = repo.save(record)
    loaded = repo.find_by_id(saved.id)

    assert loaded == record


def test_workflow_instance_active_lookup_ignores_terminal_states(session_factory) -> None:
    repo = PostgresWorkflowInstanceRepository(session_factory)
    ticket_id = TicketId(uuid.uuid4())
    ticket_cycle_id = TicketCycleId(uuid.uuid4())
    workflow_type = WorkflowType("TICKET_TRIAGE")
    repo.save(_workflow_instance_record(
        ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_type=workflow_type, state=WorkflowState.COMPLETED
    ))

    found = repo.find_active_by_ticket_cycle_and_type(ticket_id, ticket_cycle_id, workflow_type)

    assert found is None


def test_workflow_instance_save_enforces_optimistic_concurrency(session_factory) -> None:
    repo = PostgresWorkflowInstanceRepository(session_factory)
    record = _workflow_instance_record()
    repo.save(record)

    with pytest.raises(WorkflowInstanceVersionConflictException):
        repo.save(_workflow_instance_record(id=record.id, workflow_version=3))


def test_workflow_instance_unique_active_index_rejects_a_second_active_row(session_factory) -> None:
    repo = PostgresWorkflowInstanceRepository(session_factory)
    ticket_id = TicketId(uuid.uuid4())
    ticket_cycle_id = TicketCycleId(uuid.uuid4())
    workflow_type = WorkflowType("TICKET_TRIAGE")
    repo.save(_workflow_instance_record(ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_type=workflow_type))

    with pytest.raises(WorkflowInstanceVersionConflictException):
        repo.save(_workflow_instance_record(ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_type=workflow_type))


def test_workflow_instance_find_by_ticket_id_returns_every_instance_regardless_of_state(session_factory) -> None:
    repo = PostgresWorkflowInstanceRepository(session_factory)
    ticket_id = TicketId(uuid.uuid4())
    first_cycle = _workflow_instance_record(ticket_id=ticket_id, state=WorkflowState.COMPLETED)
    second_cycle = _workflow_instance_record(ticket_id=ticket_id, state=WorkflowState.RUNNING)
    other_ticket = _workflow_instance_record()
    repo.save(first_cycle)
    repo.save(second_cycle)
    repo.save(other_ticket)

    found = repo.find_by_ticket_id(ticket_id)

    assert {record.id for record in found} == {first_cycle.id, second_cycle.id}


def test_agent_task_round_trips_and_enforces_version(session_factory) -> None:
    repo = PostgresAgentTaskRepository(session_factory)
    workflow_instance_id = WorkflowInstanceId.new_id()
    # A real FK target is required by the schema (agent_tasks.workflow_instance_id references
    # workflow_instances.id).
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    record = _agent_task_record(workflow_instance_id, depends_on_task_keys=frozenset({"a", "b"}))

    saved = repo.save(record)
    loaded = repo.find_by_workflow_instance_id_and_task_key(workflow_instance_id, "collect")

    assert loaded == record
    assert saved == record

    with pytest.raises(AgentTaskVersionConflictException):
        repo.save(_agent_task_record(
            workflow_instance_id, id=record.id, state=AgentTaskState.CLAIMED, task_version=5, worker_id="worker-1"
        ))


def test_agent_task_save_is_safe_under_concurrent_claim_attempts(session_factory) -> None:
    """09-concurrency-and-idempotency §"Task Claim": "Use FOR UPDATE SKIP LOCKED or an
    equivalent mechanism to prevent multiple workers from claiming the same task." Fires
    20 concurrent `save()` calls all racing to move the same PENDING row to task_version=2;
    exactly one must win, the rest must all raise the conflict exception — never silently
    overwrite each other (the "lost update" PostgresWorkflowInstanceRepository.save()'s
    docstring warns about).
    """
    repo = PostgresAgentTaskRepository(session_factory)
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    original = repo.save(_agent_task_record(workflow_instance_id))

    def attempt_claim(worker_index: int) -> str:
        try:
            repo.save(_agent_task_record(
                workflow_instance_id, id=original.id, state=AgentTaskState.CLAIMED, task_version=2,
                worker_id=f"worker-{worker_index}",
            ))
            return "won"
        except AgentTaskVersionConflictException:
            return "lost"

    concurrency = 10
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        outcomes = list(executor.map(attempt_claim, range(concurrency)))

    assert outcomes.count("won") == 1
    assert outcomes.count("lost") == concurrency - 1

    final = repo.find_by_id(original.id)
    assert final.state is AgentTaskState.CLAIMED
    assert final.task_version == 2


def test_agent_task_find_claimable_ready_tasks_filters_by_role_and_state(session_factory) -> None:
    repo = PostgresAgentTaskRepository(session_factory)
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    matching = repo.save(_agent_task_record(
        workflow_instance_id, task_key="collect", state=AgentTaskState.READY, agent_role="triage_agent"
    ))
    repo.save(_agent_task_record(
        workflow_instance_id, id=AgentTaskId.new_id(), task_key="other-role", state=AgentTaskState.READY, agent_role="kb_agent"
    ))
    repo.save(_agent_task_record(
        workflow_instance_id, id=AgentTaskId.new_id(), task_key="not-ready", state=AgentTaskState.CLAIMED, agent_role="triage_agent"
    ))

    found = repo.find_claimable_ready_tasks("triage_agent", limit=10)

    assert [record.id for record in found] == [matching.id]


def test_agent_task_find_claimable_ready_tasks_respects_the_limit(session_factory) -> None:
    repo = PostgresAgentTaskRepository(session_factory)
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    for i in range(3):
        repo.save(_agent_task_record(
            workflow_instance_id, id=AgentTaskId.new_id(), task_key=f"collect-{i}", state=AgentTaskState.READY,
            agent_role="triage_agent", created_at=NOW + timedelta(seconds=i),
        ))

    found = repo.find_claimable_ready_tasks("triage_agent", limit=2)

    assert len(found) == 2


def test_claim_ready_is_safe_under_concurrent_batch_claims_against_a_shared_pool(session_factory) -> None:
    """09-concurrency-and-idempotency §"Task Claim": "Use FOR UPDATE SKIP LOCKED or an
    equivalent mechanism to prevent multiple workers from claiming the same task." Ten
    READY tasks, ten concurrent workers polling (retrying, like a real Agent Worker loop,
    whenever a poll returns fewer than requested — ClaimAgentTaskService.claim_ready()'s
    own docstring: "a batch claim returning fewer than max_tasks is an ordinary, expected
    outcome for a polling worker") for one task each via the real application service
    (not the repository directly). Every task must end up claimed by exactly one worker,
    none lost, none double-claimed, whether that guarantee comes from SKIP LOCKED
    excluding an in-flight row or (the actual, unconditional backstop) a version conflict
    on save() that claim_ready() quietly retries past.
    """
    workflow_instance_repository = PostgresWorkflowInstanceRepository(session_factory)
    agent_task_repository = PostgresAgentTaskRepository(session_factory)
    workflow_instance_id = WorkflowInstanceId.new_id()
    workflow_instance_repository.save(_workflow_instance_record(id=workflow_instance_id))
    pool_size = 10
    task_ids = set()
    for i in range(pool_size):
        saved = agent_task_repository.save(_agent_task_record(
            workflow_instance_id, id=AgentTaskId.new_id(), task_key=f"collect-{i}", state=AgentTaskState.READY,
            agent_role="triage_agent", created_at=NOW + timedelta(seconds=i),
        ))
        task_ids.add(saved.id)

    service = ClaimAgentTaskService(agent_task_repository, workflow_instance_repository, SystemClockAdapter())

    def attempt_claim(worker_index: int) -> list:
        command = ClaimReadyAgentTasksCommand("triage_agent", f"worker-{worker_index}", 1, 300)
        for _ in range(pool_size):  # generous retry ceiling; a real worker just polls again next tick
            claimed = service.claim_ready(command)
            if claimed:
                return claimed
        return []

    with ThreadPoolExecutor(max_workers=pool_size) as executor:
        results = list(executor.map(attempt_claim, range(pool_size)))

    claimed_ids = [view.agent_task_id for views in results for view in views]
    assert len(claimed_ids) == pool_size
    assert set(claimed_ids) == task_ids
    assert len(set(claimed_ids)) == pool_size  # no task claimed twice


def test_checkpoint_save_computes_a_checksum(session_factory) -> None:
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    repo = PostgresCheckpointRepository(session_factory)
    record = CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.PRE_TOOL_CALL,
        schema_version=1, payload='{"step":1}', recorded_at=NOW,
    )

    repo.save(record)
    loaded = repo.find_by_workflow_instance_id(workflow_instance_id)

    assert loaded == [record]


def test_checkpoint_find_latest_returns_the_most_recently_recorded_row(session_factory) -> None:
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    repo = PostgresCheckpointRepository(session_factory)
    older = CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload="{}", recorded_at=NOW,
    )
    newer = CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.PRE_TOOL_CALL,
        schema_version=1, payload="{}", recorded_at=NOW + timedelta(minutes=5),
    )
    repo.save(older)
    repo.save(newer)

    latest = repo.find_latest_by_workflow_instance_id(workflow_instance_id)

    assert latest.id == newer.id


def test_checkpoint_find_latest_returns_none_when_the_instance_has_no_checkpoint(session_factory) -> None:
    repo = PostgresCheckpointRepository(session_factory)

    assert repo.find_latest_by_workflow_instance_id(WorkflowInstanceId.new_id()) is None


def test_checkpoint_find_latest_by_type_filters_to_the_matching_checkpoint_type(session_factory) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 step 6: CoordinateAgentTasksService.
    unlock_downstream_tasks() relies on this to find the STARTED checkpoint specifically,
    even once other checkpoint types (AFTER_TASK, PRE_TOOL_CALL) also exist for the
    instance.
    """
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    repo = PostgresCheckpointRepository(session_factory)
    started = CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.STARTED,
        schema_version=1, payload='{"taskGraph":[]}', recorded_at=NOW,
    )
    after_task = CheckpointRecord(
        id=CheckpointId.new_id(), workflow_instance_id=workflow_instance_id, type=CheckpointType.AFTER_TASK,
        schema_version=1, payload="{}", recorded_at=NOW + timedelta(minutes=5),
    )
    repo.save(started)
    repo.save(after_task)

    found = repo.find_latest_by_workflow_instance_id_and_type(workflow_instance_id, CheckpointType.STARTED)

    assert found.id == started.id


def test_checkpoint_find_latest_by_type_returns_none_when_no_checkpoint_of_that_type_exists(session_factory) -> None:
    repo = PostgresCheckpointRepository(session_factory)

    assert repo.find_latest_by_workflow_instance_id_and_type(WorkflowInstanceId.new_id(), CheckpointType.STARTED) is None


def test_tool_request_completed_at_is_derived_from_terminal_status(session_factory) -> None:
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    agent_task_id = AgentTaskId.new_id()
    PostgresAgentTaskRepository(session_factory).save(_agent_task_record(
        workflow_instance_id, id=agent_task_id, state=AgentTaskState.CLAIMED, worker_id="worker-1",
    ))
    checkpoint_id = CheckpointId.new_id()
    PostgresCheckpointRepository(session_factory).save(CheckpointRecord(
        id=checkpoint_id, workflow_instance_id=workflow_instance_id, type=CheckpointType.PRE_TOOL_CALL,
        schema_version=1, payload="{}", recorded_at=NOW,
    ))
    repo = PostgresToolRequestRepository(session_factory)
    tool_request_id = ToolRequestId.new_id()

    repo.save(ToolRequestRecord(
        id=tool_request_id, workflow_instance_id=workflow_instance_id, agent_task_id=agent_task_id,
        preceding_checkpoint_id=checkpoint_id, tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.PENDING, created_at=NOW, updated_at=NOW,
    ))
    repo.save(ToolRequestRecord(
        id=tool_request_id, workflow_instance_id=workflow_instance_id, agent_task_id=agent_task_id,
        preceding_checkpoint_id=checkpoint_id, tool_name="restart_service", request_payload="{}",
        status=ToolRequestStatus.COMPLETED, created_at=NOW, updated_at=NOW,
    ))

    loaded = repo.find_by_id(tool_request_id)

    assert loaded.status is ToolRequestStatus.COMPLETED


def test_processed_event_dedup(session_factory) -> None:
    repo = PostgresProcessedEventRepository(session_factory)

    assert repo.is_processed("evt-1") is False
    repo.mark_processed("evt-1", NOW)
    assert repo.is_processed("evt-1") is True
    # Marking an already-processed event again must stay a no-op, not raise.
    repo.mark_processed("evt-1", NOW)


def _outbox_record(workflow_instance_id: WorkflowInstanceId, **overrides) -> OutboxRecord:
    defaults = dict(
        outbox_id=uuid.uuid4(), workflow_instance_id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()),
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type="agent_runtime.workflow.started",
        schema_version=1, payload="{}", occurred_at=NOW,
    )
    defaults.update(overrides)
    return OutboxRecord(**defaults)


def test_outbox_append_derives_aggregate_type_from_event_type(session_factory) -> None:
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    repo = PostgresOutboxRepository(session_factory)

    repo.append(_outbox_record(workflow_instance_id, event_type="agent_runtime.agent_task.completed"))

    [due] = repo.find_dispatchable(NOW + timedelta(seconds=1), 10)
    assert due.status is OutboxStatus.PENDING
    assert due.attempts == 0


def test_outbox_dispatch_lifecycle_publish_retry_and_dead_letter(session_factory) -> None:
    """08-transaction-and-outbox §"Outbox Publisher": scan by available_at, mark
    published_at on success, retry with backoff on failure, dead-letter after repeats.
    """
    workflow_instance_id = WorkflowInstanceId.new_id()
    PostgresWorkflowInstanceRepository(session_factory).save(_workflow_instance_record(id=workflow_instance_id))
    repo = PostgresOutboxRepository(session_factory)

    published_id = uuid.uuid4()
    repo.append(_outbox_record(workflow_instance_id, outbox_id=published_id))
    repo.mark_published(published_id, NOW)
    assert repo.find_dispatchable(NOW + timedelta(seconds=1), 10) == []

    retried_id = uuid.uuid4()
    repo.append(_outbox_record(workflow_instance_id, outbox_id=retried_id))
    repo.mark_failed(retried_id, NOW + timedelta(minutes=5), 1)
    assert repo.find_dispatchable(NOW + timedelta(seconds=1), 10) == []
    [due] = repo.find_dispatchable(NOW + timedelta(minutes=10), 10)
    assert due.outbox_id == retried_id
    assert due.attempts == 1

    dead_id = uuid.uuid4()
    repo.append(_outbox_record(workflow_instance_id, outbox_id=dead_id))
    repo.mark_dead_letter(dead_id)
    assert all(record.outbox_id != dead_id for record in repo.find_dispatchable(NOW + timedelta(days=1), 10))


def test_command_idempotency_round_trip(session_factory) -> None:
    repo = PostgresCommandIdempotencyRepository(session_factory)
    key = IdempotencyKey("pause-1")
    record = CommandIdempotencyRecord(
        idempotency_key=key, command_type="pause_workflow", target_id=str(uuid.uuid4()), request_hash="abc123",
        response_json='{"state":"PAUSED"}', created_at=NOW, expires_at=None,
    )

    repo.save(record)
    loaded = repo.find_by_key(key)

    assert loaded == record
    assert repo.find_by_key(IdempotencyKey("unknown")) is None
