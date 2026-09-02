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
    AuditRecordEntry,
    CheckpointRecord,
    CommandIdempotencyRecord,
    OutboxRecord,
    PoisonEventRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.domain.enums import AgentTaskState, CheckpointType, OutboxStatus, ToolRequestStatus
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

    def find_non_terminal(self, limit: int) -> list[WorkflowInstanceRecord]:
        matching = [record for record in self._store.values() if not record.state.is_terminal()]
        matching.sort(key=lambda record: record.updated_at)
        return matching[:limit]

    def find_most_recent_by_requester_and_workflow_type(
        self, requester_subject: str, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        matching = [
            record for record in self._store.values()
            if record.requester_subject == requester_subject and record.workflow_type == workflow_type
        ]
        if not matching:
            return None
        return max(matching, key=lambda record: record.created_at)

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

    def find_expired_leases(self, now: datetime, limit: int) -> list[AgentTaskRecord]:
        matching = [
            record for record in self._store.values()
            if record.state in (AgentTaskState.CLAIMED, AgentTaskState.RUNNING)
            and record.lease_expires_at is not None and record.lease_expires_at < now
        ]
        matching.sort(key=lambda record: record.lease_expires_at)
        return matching[:limit]


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

    def find_pending(self, limit: int) -> list[ToolRequestRecord]:
        pending = sorted(
            (record for record in self._store.values() if record.status == ToolRequestStatus.PENDING),
            key=lambda record: record.created_at,
        )
        return pending[:limit]


class InMemoryProcessedEventRepository:
    """Fast, hermetic test double for the "consumed event dedup" contract
    (02-business-invariants §"Event Handling Invariants"). infrastructure.persistence.
    postgres.repositories.PostgresProcessedEventRepository is the durable adapter real
    runs use. Keyed by (event_id, consumer_name) — mirrors the Postgres composite primary
    key (07-data-model) so two distinct consumers processing the same event_id never
    collide (SPEC-ARO-013).
    """

    def __init__(self) -> None:
        self._store: dict[tuple[str, str], datetime] = {}
        self._lock = threading.Lock()

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        return (event_id, consumer_name) in self._store

    def mark_processed(
        self,
        event_id: str,
        consumer_name: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None:
        with self._lock:
            self._store.setdefault((event_id, consumer_name), processed_at)


class InMemoryPoisonEventRepository:
    """SPEC-ARO-024 10-failure-handling §"Poison Event". Deliberately NOT keyed the same
    way as processed_events (event_id, consumer_name) — a poisoned delivery may be
    recorded more than once if it is replayed and fails again before being fixed, so
    each record() call appends rather than deduplicating.
    """

    def __init__(self) -> None:
        self._store: list[PoisonEventRecord] = []
        self._lock = threading.Lock()

    def record(self, record: PoisonEventRecord) -> PoisonEventRecord:
        with self._lock:
            self._store.append(record)
        return record

    def find_all(self, limit: int) -> list[PoisonEventRecord]:
        return sorted(self._store, key=lambda record: record.recorded_at, reverse=True)[:limit]

    def find_by_id(self, id: uuid.UUID) -> PoisonEventRecord | None:
        return next((record for record in self._store if record.id == id), None)

    def mark_quarantined(self, id: uuid.UUID, quarantined_at: datetime) -> None:
        with self._lock:
            for index, record in enumerate(self._store):
                if record.id == id:
                    self._store[index] = dataclasses.replace(record, quarantined_at=quarantined_at)
                    return


class InMemoryAuditRecordRepository:
    """SPEC-ARO-034 12-observability §"Audit Events". Append-only, like
    InMemoryPoisonEventRepository — never mutated after being written.
    """

    def __init__(self) -> None:
        self._store: list[AuditRecordEntry] = []
        self._lock = threading.Lock()

    def append(self, entry: AuditRecordEntry) -> None:
        with self._lock:
            self._store.append(entry)

    def find_all(self, limit: int) -> list[AuditRecordEntry]:
        return sorted(self._store, key=lambda entry: entry.occurred_at, reverse=True)[:limit]


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

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        dead_lettered = [record for record in self._records.values() if record.status is OutboxStatus.DEAD_LETTER]
        dead_lettered.sort(key=lambda record: record.occurred_at)
        return dead_lettered[:limit]

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = dataclasses.replace(
                record, status=OutboxStatus.PENDING, attempts=0, available_at=available_at, published_at=None,
            )

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
