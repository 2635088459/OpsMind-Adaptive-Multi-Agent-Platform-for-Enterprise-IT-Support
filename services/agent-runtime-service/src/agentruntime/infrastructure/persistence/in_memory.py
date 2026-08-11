"""SPEC-ARO-001 module boundary placeholders: in-process, non-durable
implementations of the agentruntime.application.ports_out repository protocols.
SPEC-ARO-002 (schema baseline) adds real SQLAlchemy/Postgres-backed adapters
(infrastructure.persistence.postgres) for production and integration tests; these
in-memory adapters stay in use for fast, hermetic unit tests (see
agentruntime.settings.Settings.agent_runtime_persistence) and must not be used
beyond a single process/test lifetime.
"""

from __future__ import annotations

import dataclasses
import threading
import uuid
from datetime import datetime

from agentruntime.application.exceptions import AgentTaskVersionConflictException, WorkflowInstanceVersionConflictException
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    CommandIdempotencyRecord,
    OutboxRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.domain.enums import AgentTaskState, CheckpointType, OutboxStatus
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowInstanceId,
    WorkflowType,
)


class InMemoryWorkflowInstanceRepository:
    def __init__(self) -> None:
        self._store: dict[WorkflowInstanceId, WorkflowInstanceRecord] = {}
        self._lock = threading.Lock()

    def find_by_id(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceRecord | None:
        return self._store.get(workflow_instance_id)

    def find_active_by_ticket_cycle_and_type(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        for record in self._store.values():
            if (
                record.ticket_id == ticket_id
                and record.ticket_cycle_id == ticket_cycle_id
                and record.workflow_type == workflow_type
                and not record.state.is_terminal()
            ):
                return record
        return None

    def find_by_ticket_id(self, ticket_id: TicketId) -> list[WorkflowInstanceRecord]:
        return [record for record in self._store.values() if record.ticket_id == ticket_id]

    def save(self, record: WorkflowInstanceRecord) -> WorkflowInstanceRecord:
        with self._lock:
            existing = self._store.get(record.id)
            expected_previous_version = record.workflow_version - 1
            if existing is None:
                if expected_previous_version != 0:
                    raise WorkflowInstanceVersionConflictException()
            elif existing.workflow_version != expected_previous_version:
                raise WorkflowInstanceVersionConflictException()
            self._store[record.id] = record
            return record


class InMemoryAgentTaskRepository:
    def __init__(self) -> None:
        self._store: dict[AgentTaskId, AgentTaskRecord] = {}
        self._lock = threading.Lock()

    def find_by_id(self, agent_task_id: AgentTaskId) -> AgentTaskRecord | None:
        return self._store.get(agent_task_id)

    def find_by_workflow_instance_id_and_task_key(
        self, workflow_instance_id: WorkflowInstanceId, task_key: str
    ) -> AgentTaskRecord | None:
        for record in self._store.values():
            if record.workflow_instance_id == workflow_instance_id and record.task_key == task_key:
                return record
        return None

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[AgentTaskRecord]:
        return [record for record in self._store.values() if record.workflow_instance_id == workflow_instance_id]

    def find_claimable_ready_tasks(self, agent_role: str, limit: int) -> list[AgentTaskRecord]:
        matching = [
            record for record in self._store.values()
            if record.agent_role == agent_role and record.state is AgentTaskState.READY
        ]
        matching.sort(key=lambda record: record.created_at)
        return matching[:limit]

    def save(self, record: AgentTaskRecord) -> AgentTaskRecord:
        with self._lock:
            existing = self._store.get(record.id)
            expected_previous_version = record.task_version - 1
            if existing is None:
                if expected_previous_version != 0:
                    raise AgentTaskVersionConflictException()
            elif existing.task_version != expected_previous_version:
                raise AgentTaskVersionConflictException()
            self._store[record.id] = record
            return record


class InMemoryCheckpointRepository:
    def __init__(self) -> None:
        self._store: dict[CheckpointId, CheckpointRecord] = {}
        self._lock = threading.Lock()

    def save(self, record: CheckpointRecord) -> CheckpointRecord:
        with self._lock:
            self._store[record.id] = record
            return record

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[CheckpointRecord]:
        return [record for record in self._store.values() if record.workflow_instance_id == workflow_instance_id]

    def find_latest_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointRecord | None:
        matching = [record for record in self._store.values() if record.workflow_instance_id == workflow_instance_id]
        return max(matching, key=lambda record: record.recorded_at, default=None)

    def find_latest_by_workflow_instance_id_and_type(
        self, workflow_instance_id: WorkflowInstanceId, checkpoint_type: CheckpointType
    ) -> CheckpointRecord | None:
        matching = [
            record for record in self._store.values()
            if record.workflow_instance_id == workflow_instance_id and record.type is checkpoint_type
        ]
        return max(matching, key=lambda record: record.recorded_at, default=None)


class InMemoryToolRequestRepository:
    def __init__(self) -> None:
        self._store: dict[ToolRequestId, ToolRequestRecord] = {}
        self._lock = threading.Lock()

    def save(self, record: ToolRequestRecord) -> ToolRequestRecord:
        with self._lock:
            self._store[record.id] = record
            return record

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequestRecord | None:
        return self._store.get(tool_request_id)


class InMemoryProcessedEventRepository:
    """Fast, hermetic test double for the "consumed event dedup" contract
    (02-business-invariants §"Event Handling Invariants"). infrastructure.persistence.
    postgres.repositories.PostgresProcessedEventRepository is the durable adapter real
    runs use.
    """

    def __init__(self) -> None:
        self._store: dict[str, datetime] = {}
        self._lock = threading.Lock()

    def is_processed(self, event_id: str) -> bool:
        return event_id in self._store

    def mark_processed(
        self,
        event_id: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None:
        with self._lock:
            self._store.setdefault(event_id, processed_at)


class InMemoryOutboxRepository:
    """Fast, hermetic test double for "every published event must go through outbox"
    (02-business-invariants §"Event Handling Invariants") and for
    DispatchOutboxEventsService's scan/publish/retry/dead-letter cycle
    (08-transaction-and-outbox §"Outbox Publisher").
    """

    def __init__(self) -> None:
        self._records: dict[uuid.UUID, OutboxRecord] = {}
        self._lock = threading.Lock()

    def append(self, record: OutboxRecord) -> None:
        with self._lock:
            self._records[record.outbox_id] = record

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        due = [
            record for record in self._records.values()
            if record.status is OutboxStatus.PENDING and record.available_at <= now
        ]
        due.sort(key=lambda record: record.available_at)
        return due[:limit]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.PUBLISHED, published_at=published_at)

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, attempts=attempts, available_at=next_available_at)

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(record, status=OutboxStatus.DEAD_LETTER)

    def recorded(self) -> list[OutboxRecord]:
        """Test/inspection seam only — DispatchOutboxEventsService reads through find_dispatchable(), not this method."""
        return list(self._records.values())


class InMemoryCommandIdempotencyRepository:
    """Fast, hermetic test double for the command_idempotency table (07-data-model);
    see CommandIdempotencyRecord's docstring for who uses it.
    """

    def __init__(self) -> None:
        self._store: dict[IdempotencyKey, CommandIdempotencyRecord] = {}
        self._lock = threading.Lock()

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        return self._store.get(idempotency_key)

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        with self._lock:
            self._store[record.idempotency_key] = record
            return record
