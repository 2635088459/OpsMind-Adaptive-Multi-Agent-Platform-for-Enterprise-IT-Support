"""SPEC-ARO-002/003: SQLAlchemy/Postgres-backed implementations of every
agentruntime.application.ports_out repository protocol. Each repository opens
one short-lived Session per call (`with self._session_factory() as session:`) —
real cross-repository transaction boundaries (e.g. "checkpoint and outbox commit
atomically", 08-transaction-and-outbox) land with a later spec once Runtime needs
to coordinate more than one aggregate write per request; every individual write
here is already atomic and safe under concurrent access (see save()'s docstrings).
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import desc, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

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
from agentruntime.domain.enums import AgentTaskState, CheckpointType, OutboxStatus, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CausationId,
    CheckpointId,
    CorrelationId,
    DefinitionVersion,
    IdempotencyKey,
    LeaseToken,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.postgres.models import (
    AgentTaskRow,
    AuditEventRow,
    CheckpointRow,
    CommandIdempotencyRow,
    OutboxEventRow,
    PoisonEventRow,
    ProcessedEventRow,
    ToolRequestRow,
    WorkflowInstanceRow,
)

_TERMINAL_TOOL_REQUEST_STATUSES = frozenset({ToolRequestStatus.COMPLETED, ToolRequestStatus.FAILED})


def _to_workflow_instance_record(row: WorkflowInstanceRow) -> WorkflowInstanceRecord:
    return WorkflowInstanceRecord(
        id=WorkflowInstanceId(row.id), ticket_id=TicketId(row.ticket_id), ticket_cycle_id=TicketCycleId(row.ticket_cycle_id),
        workflow_type=WorkflowType(row.workflow_type), definition_id=WorkflowDefinitionId(row.definition_id),
        definition_version=DefinitionVersion(row.definition_version), state=WorkflowState[row.state],
        workflow_version=row.workflow_version, pause_generation=row.pause_generation,
        current_checkpoint_id=CheckpointId(row.current_checkpoint_id) if row.current_checkpoint_id else None,
        completed_at=row.completed_at, created_at=row.created_at, updated_at=row.updated_at,
    )


class PostgresWorkflowInstanceRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceRecord | None:
        with self._session_factory() as session:
            row = session.get(WorkflowInstanceRow, workflow_instance_id.value)
            return _to_workflow_instance_record(row) if row else None

    def find_active_by_ticket_cycle_and_type(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        terminal = {s.name for s in WorkflowState if s.is_terminal()}
        with self._session_factory() as session:
            stmt = select(WorkflowInstanceRow).where(
                WorkflowInstanceRow.ticket_id == ticket_id.value,
                WorkflowInstanceRow.ticket_cycle_id == ticket_cycle_id.value,
                WorkflowInstanceRow.workflow_type == str(workflow_type),
                WorkflowInstanceRow.state.not_in(terminal),
            )
            row = session.execute(stmt).scalars().first()
            return _to_workflow_instance_record(row) if row else None

    def find_by_ticket_id(self, ticket_id: TicketId) -> list[WorkflowInstanceRecord]:
        with self._session_factory() as session:
            stmt = select(WorkflowInstanceRow).where(WorkflowInstanceRow.ticket_id == ticket_id.value).order_by(WorkflowInstanceRow.created_at)
            rows = session.execute(stmt).scalars().all()
            return [_to_workflow_instance_record(row) for row in rows]

    def find_non_terminal(self, limit: int) -> list[WorkflowInstanceRecord]:
        terminal = {s.name for s in WorkflowState if s.is_terminal()}
        with self._session_factory() as session:
            stmt = (
                select(WorkflowInstanceRow)
                .where(WorkflowInstanceRow.state.not_in(terminal))
                .order_by(WorkflowInstanceRow.updated_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_workflow_instance_record(row) for row in rows]

    def save(self, record: WorkflowInstanceRecord) -> WorkflowInstanceRecord:
        """09-concurrency-and-idempotency §"Concurrency Model": "guaranteed by database
        locks, optimistic versions, unique keys." The UPDATE path is a single atomic
        `UPDATE ... WHERE id = :id AND workflow_version = :expected_previous_version`
        statement — SQLAlchemy's ORM session.get()-then-mutate-then-commit pattern does
        NOT do this (its generated UPDATE has no version predicate at all, so two
        concurrent callers reading the same version would both "succeed", the second
        silently clobbering the first — a lost update). Core update()/insert() bound to
        a real WHERE clause, with the affected rowcount checked, is what makes this
        genuinely compare-and-swap.
        """
        expected_previous_version = record.workflow_version - 1
        with self._session_factory() as session:
            if expected_previous_version == 0:
                try:
                    session.execute(
                        WorkflowInstanceRow.__table__.insert().values(
                            id=record.id.value, ticket_id=record.ticket_id.value, ticket_cycle_id=record.ticket_cycle_id.value,
                            workflow_type=str(record.workflow_type), definition_id=str(record.definition_id),
                            definition_version=record.definition_version.value, state=record.state.name,
                            workflow_version=record.workflow_version, pause_generation=record.pause_generation,
                            current_checkpoint_id=record.current_checkpoint_id.value if record.current_checkpoint_id else None,
                            completed_at=record.completed_at, created_at=record.created_at, updated_at=record.updated_at,
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise WorkflowInstanceVersionConflictException() from exc
            else:
                result = session.execute(
                    update(WorkflowInstanceRow.__table__)
                    .where(WorkflowInstanceRow.id == record.id.value, WorkflowInstanceRow.workflow_version == expected_previous_version)
                    .values(
                        state=record.state.name, workflow_version=record.workflow_version,
                        pause_generation=record.pause_generation,
                        current_checkpoint_id=record.current_checkpoint_id.value if record.current_checkpoint_id else None,
                        completed_at=record.completed_at, updated_at=record.updated_at,
                    )
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise WorkflowInstanceVersionConflictException()
                session.commit()
            return record


def _to_agent_task_record(row: AgentTaskRow) -> AgentTaskRecord:
    return AgentTaskRecord(
        id=AgentTaskId(row.id), workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id), task_key=row.task_key,
        task_type=row.task_type, depends_on_task_keys=frozenset(row.depends_on_json), state=AgentTaskState[row.state],
        task_version=row.task_version, worker_id=row.claim_owner,
        lease_token=LeaseToken(row.claim_token) if row.claim_token else None, lease_expires_at=row.claim_expires_at,
        result_payload=row.result_payload_json, failure_reason=row.failure_reason, pause_generation=row.pause_generation,
        created_at=row.created_at, updated_at=row.updated_at, agent_role=row.agent_role,
        attempt=row.attempt, max_attempts=row.max_attempts,
    )


class PostgresAgentTaskRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, agent_task_id: AgentTaskId) -> AgentTaskRecord | None:
        with self._session_factory() as session:
            row = session.get(AgentTaskRow, agent_task_id.value)
            return _to_agent_task_record(row) if row else None

    def find_by_workflow_instance_id_and_task_key(
        self, workflow_instance_id: WorkflowInstanceId, task_key: str
    ) -> AgentTaskRecord | None:
        with self._session_factory() as session:
            stmt = select(AgentTaskRow).where(
                AgentTaskRow.workflow_instance_id == workflow_instance_id.value, AgentTaskRow.task_key == task_key
            )
            row = session.execute(stmt).scalars().first()
            return _to_agent_task_record(row) if row else None

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[AgentTaskRecord]:
        with self._session_factory() as session:
            stmt = select(AgentTaskRow).where(AgentTaskRow.workflow_instance_id == workflow_instance_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_to_agent_task_record(row) for row in rows]

    def find_claimable_ready_tasks(self, agent_role: str, limit: int) -> list[AgentTaskRecord]:
        """09-concurrency-and-idempotency: "Use FOR UPDATE SKIP LOCKED or an equivalent
        mechanism". Rows another transaction already holds a lock on (mid-claim, in
        another worker's find-then-save window) are excluded from this result outright
        rather than blocking on them — ix_agent_tasks_role_state_claim_expiry
        (SPEC-ARO-002) is what keeps this an index scan, not a table scan.
        """
        with self._session_factory() as session:
            stmt = (
                select(AgentTaskRow)
                .where(AgentTaskRow.agent_role == agent_role, AgentTaskRow.state == AgentTaskState.READY.name)
                .order_by(AgentTaskRow.created_at)
                .limit(limit)
                .with_for_update(skip_locked=True)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_agent_task_record(row) for row in rows]

    def save(self, record: AgentTaskRecord) -> AgentTaskRecord:
        """09-concurrency-and-idempotency §"Task Claim": "Use FOR UPDATE SKIP LOCKED or an
        equivalent mechanism to prevent multiple workers from claiming the same task." The
        atomic `UPDATE ... WHERE id = :id AND task_version = :expected_previous_version`
        below is that equivalent mechanism: of two workers racing to claim the same PENDING
        row, both read task_version=N, both compute task_version=N+1, but only one UPDATE
        can match `task_version = N` (Postgres serializes the two UPDATEs) — the loser's
        rowcount is 0, and it gets AgentTaskVersionConflictException instead of silently
        overwriting the winner's claim. See PostgresWorkflowInstanceRepository.save()'s
        docstring for why the naive session.get()-then-commit() pattern does not have this
        property.
        """
        expected_previous_version = record.task_version - 1
        with self._session_factory() as session:
            if expected_previous_version == 0:
                try:
                    session.execute(
                        AgentTaskRow.__table__.insert().values(
                            id=record.id.value, workflow_instance_id=record.workflow_instance_id.value,
                            task_key=record.task_key, task_type=record.task_type, state=record.state.name,
                            depends_on_json=sorted(record.depends_on_task_keys), result_payload_json=record.result_payload,
                            failure_reason=record.failure_reason, claim_owner=record.worker_id,
                            claim_token=record.lease_token.value if record.lease_token else None,
                            claim_expires_at=record.lease_expires_at, pause_generation=record.pause_generation,
                            task_version=record.task_version, created_at=record.created_at, updated_at=record.updated_at,
                            agent_role=record.agent_role, attempt=record.attempt, max_attempts=record.max_attempts,
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise AgentTaskVersionConflictException() from exc
            else:
                result = session.execute(
                    update(AgentTaskRow.__table__)
                    .where(AgentTaskRow.id == record.id.value, AgentTaskRow.task_version == expected_previous_version)
                    .values(
                        state=record.state.name, result_payload_json=record.result_payload, failure_reason=record.failure_reason,
                        claim_owner=record.worker_id, claim_token=record.lease_token.value if record.lease_token else None,
                        claim_expires_at=record.lease_expires_at, pause_generation=record.pause_generation,
                        task_version=record.task_version, updated_at=record.updated_at,
                        attempt=record.attempt, max_attempts=record.max_attempts,
                    )
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise AgentTaskVersionConflictException()
                session.commit()
            return record

    def find_expired_leases(self, now: datetime, limit: int) -> list[AgentTaskRecord]:
        """SPEC-ARO-029: no dedicated index exists for this scan (unlike
        ix_agent_tasks_role_state_claim_expiry, which is led by agent_role for the
        claim-path lookup) — this is a low-frequency admin/ops-triggered batch scan, the
        same performance profile WorkflowInstanceRepository.find_non_terminal() already
        accepted for SPEC-ARO-028's scanner, not a per-request hot path.
        """
        with self._session_factory() as session:
            stmt = (
                select(AgentTaskRow)
                .where(
                    AgentTaskRow.state.in_([AgentTaskState.CLAIMED.name, AgentTaskState.RUNNING.name]),
                    AgentTaskRow.claim_expires_at.is_not(None),
                    AgentTaskRow.claim_expires_at < now,
                )
                .order_by(AgentTaskRow.claim_expires_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_agent_task_record(row) for row in rows]


def _to_checkpoint_record(row: CheckpointRow) -> CheckpointRecord:
    return CheckpointRecord(
        id=CheckpointId(row.id), workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id),
        type=CheckpointType[row.checkpoint_type], schema_version=row.payload_schema_version,
        payload=row.payload_json, recorded_at=row.created_at,
        workflow_version=row.workflow_version, checksum=row.checksum, cursor=row.cursor,
    )


class PostgresCheckpointRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, record: CheckpointRecord) -> CheckpointRecord:
        """SPEC-ARO-011: workflow_version/cursor/checksum are persisted verbatim from
        `record` rather than recomputed here — domain.checkpoint.record() is the one
        authoritative source for the checksum (and for defaulting cursor to None until a
        phase-06 consumer starts populating it), so this adapter stays a plain persister,
        symmetric with InMemoryCheckpointRepository's own no-mapping save().
        """
        with self._session_factory() as session:
            row = CheckpointRow(
                id=record.id.value, workflow_instance_id=record.workflow_instance_id.value,
                workflow_version=record.workflow_version, checkpoint_type=record.type.name, cursor=record.cursor,
                payload_schema_version=record.schema_version, payload_json=record.payload, checksum=record.checksum,
                created_at=record.recorded_at,
            )
            session.add(row)
            session.commit()
            return record

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[CheckpointRecord]:
        with self._session_factory() as session:
            stmt = select(CheckpointRow).where(CheckpointRow.workflow_instance_id == workflow_instance_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_to_checkpoint_record(row) for row in rows]

    def find_latest_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointRecord | None:
        with self._session_factory() as session:
            stmt = (
                select(CheckpointRow)
                .where(CheckpointRow.workflow_instance_id == workflow_instance_id.value)
                .order_by(desc(CheckpointRow.created_at))
                .limit(1)
            )
            row = session.execute(stmt).scalars().first()
            return _to_checkpoint_record(row) if row is not None else None

    def find_latest_by_workflow_instance_id_and_type(
        self, workflow_instance_id: WorkflowInstanceId, checkpoint_type: CheckpointType
    ) -> CheckpointRecord | None:
        with self._session_factory() as session:
            stmt = (
                select(CheckpointRow)
                .where(CheckpointRow.workflow_instance_id == workflow_instance_id.value, CheckpointRow.checkpoint_type == checkpoint_type.name)
                .order_by(desc(CheckpointRow.created_at))
                .limit(1)
            )
            row = session.execute(stmt).scalars().first()
            return _to_checkpoint_record(row) if row is not None else None


def _to_tool_request_record(row: ToolRequestRow) -> ToolRequestRecord:
    return ToolRequestRecord(
        id=ToolRequestId(row.id), workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id),
        agent_task_id=AgentTaskId(row.agent_task_id), preceding_checkpoint_id=CheckpointId(row.preceding_checkpoint_id),
        tool_name=row.tool_name, request_payload=row.input_payload_json, status=ToolRequestStatus[row.state],
        created_at=row.created_at, updated_at=row.updated_at,
        capability=row.capability, gateway_correlation_id=str(row.gateway_correlation_id) if row.gateway_correlation_id else None,
        policy_snapshot=row.policy_snapshot_json, result_payload=row.result_payload_json, idempotency_key=row.idempotency_key,
    )


class PostgresToolRequestRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, record: ToolRequestRecord) -> ToolRequestRecord:
        """SPEC-ARO-017: capability/gateway_correlation_id/policy_snapshot/result_payload/
        idempotency_key are persisted verbatim from `record`, the same plain-persister
        symmetry PostgresCheckpointRepository.save() follows (see its own docstring) —
        this adapter invents no values, it only stops discarding the ones the
        application layer already computed.
        """
        completed_at = record.updated_at if record.status in _TERMINAL_TOOL_REQUEST_STATUSES else None
        with self._session_factory() as session:
            row = session.get(ToolRequestRow, record.id.value)
            if row is None:
                row = ToolRequestRow(id=record.id.value, created_at=record.created_at)
                session.add(row)
            row.workflow_instance_id = record.workflow_instance_id.value
            row.agent_task_id = record.agent_task_id.value
            row.preceding_checkpoint_id = record.preceding_checkpoint_id.value
            row.tool_name = record.tool_name
            row.capability = record.capability
            row.gateway_correlation_id = uuid.UUID(record.gateway_correlation_id) if record.gateway_correlation_id else None
            row.policy_snapshot_json = record.policy_snapshot
            row.idempotency_key = record.idempotency_key
            row.state = record.status.name
            row.input_payload_json = record.request_payload
            row.result_payload_json = record.result_payload
            row.updated_at = record.updated_at
            row.completed_at = completed_at
            session.commit()
            return record

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequestRecord | None:
        with self._session_factory() as session:
            row = session.get(ToolRequestRow, tool_request_id.value)
            return _to_tool_request_record(row) if row is not None else None

    def find_pending(self, limit: int) -> list[ToolRequestRecord]:
        """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6:
        DispatchToolRequestsService's own scan — mirrors PostgresOutboxRepository.
        find_dispatchable()'s "scan by available_at" shape, ordered oldest-first so a
        backlog drains in submission order.
        """
        with self._session_factory() as session:
            stmt = (
                select(ToolRequestRow)
                .where(ToolRequestRow.state == ToolRequestStatus.PENDING.name)
                .order_by(ToolRequestRow.created_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_tool_request_record(row) for row in rows]


class PostgresProcessedEventRepository:
    """SPEC-ARO-013 09-concurrency-and-idempotency §"消费事件幂等": dedup is keyed by
    (event_id, consumer_name), not event_id alone — consumer_name is a caller-supplied
    identity, one constant per logical consumer (ConsumeTicketCreatedService,
    ConsumeRuntimeEventService), never invented or defaulted here.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._session_factory() as session:
            row = session.get(ProcessedEventRow, (event_id, consumer_name))
            return row is not None

    def mark_processed(
        self,
        event_id: str,
        consumer_name: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None:
        with self._session_factory() as session:
            existing = session.get(ProcessedEventRow, (event_id, consumer_name))
            if existing is not None:
                return
            row = ProcessedEventRow(
                event_id=event_id, consumer_name=consumer_name, event_type=event_type,
                workflow_instance_id=workflow_instance_id.value if workflow_instance_id else None, processed_at=processed_at,
            )
            session.add(row)
            try:
                session.commit()
            except IntegrityError:
                # Two workers racing to mark the same (event_id, consumer_name): whichever
                # loses is a no-op, not an error (02-business-invariants: duplicate events
                # must not advance Workflow again).
                session.rollback()


class PostgresPoisonEventRepository:
    """SPEC-ARO-024 10-failure-handling §"Poison Event". No uniqueness constraint beyond
    the surrogate `id` — unlike processed_events, a replayed-and-still-broken delivery is
    recorded again, not deduplicated.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def record(self, record: PoisonEventRecord) -> PoisonEventRecord:
        with self._session_factory() as session:
            row = PoisonEventRow(
                id=record.id, event_id=record.event_id, consumer_name=record.consumer_name, event_type=record.event_type,
                payload=record.payload, error_message=record.error_message, occurred_at=record.occurred_at,
                recorded_at=record.recorded_at, quarantined_at=record.quarantined_at,
            )
            session.add(row)
            session.commit()
        return record

    def find_by_id(self, id: uuid.UUID) -> PoisonEventRecord | None:
        with self._session_factory() as session:
            row = session.get(PoisonEventRow, id)
            return _to_poison_event_record(row) if row else None

    def mark_quarantined(self, id: uuid.UUID, quarantined_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(update(PoisonEventRow.__table__).where(PoisonEventRow.id == id).values(quarantined_at=quarantined_at))
            session.commit()

    def find_all(self, limit: int) -> list[PoisonEventRecord]:
        with self._session_factory() as session:
            stmt = select(PoisonEventRow).order_by(PoisonEventRow.recorded_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_to_poison_event_record(row) for row in rows]


def _to_poison_event_record(row: PoisonEventRow) -> PoisonEventRecord:
    return PoisonEventRecord(
        id=row.id, event_id=row.event_id, consumer_name=row.consumer_name, event_type=row.event_type,
        payload=row.payload, error_message=row.error_message, occurred_at=row.occurred_at, recorded_at=row.recorded_at,
        quarantined_at=row.quarantined_at,
    )


class PostgresAuditRecordRepository:
    """SPEC-ARO-034 12-observability §"Audit Events". Append-only, mirroring
    PostgresPoisonEventRepository's own shape.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, entry: AuditRecordEntry) -> None:
        with self._session_factory() as session:
            row = AuditEventRow(
                id=entry.id, audit_type=entry.audit_type, action=entry.action, resource_type=entry.resource_type,
                resource_id=entry.resource_id,
                workflow_instance_id=entry.workflow_instance_id.value if entry.workflow_instance_id else None,
                ticket_id=entry.ticket_id.value if entry.ticket_id else None,
                actor_type=entry.actor_type, actor_id=entry.actor_id, outcome=entry.outcome,
                correlation_id=entry.correlation_id, causation_id=entry.causation_id, detail=entry.detail,
                occurred_at=entry.occurred_at,
            )
            session.add(row)
            session.commit()

    def find_all(self, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = select(AuditEventRow).order_by(AuditEventRow.occurred_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_to_audit_record_entry(row) for row in rows]


def _to_audit_record_entry(row: AuditEventRow) -> AuditRecordEntry:
    return AuditRecordEntry(
        id=row.id, audit_type=row.audit_type, action=row.action, resource_type=row.resource_type,
        resource_id=row.resource_id,
        workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id) if row.workflow_instance_id else None,
        ticket_id=TicketId(row.ticket_id) if row.ticket_id else None,
        actor_type=row.actor_type, actor_id=row.actor_id, outcome=row.outcome,
        correlation_id=row.correlation_id, causation_id=row.causation_id, detail=row.detail, occurred_at=row.occurred_at,
    )


def _outbox_aggregate_type(event_type: str) -> str:
    return "AgentTask" if event_type.startswith("agent_runtime.agent_task.") else "WorkflowInstance"


def _to_outbox_record(row: OutboxEventRow) -> OutboxRecord:
    return OutboxRecord(
        outbox_id=row.id, workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id), ticket_id=TicketId(row.ticket_id),
        correlation_id=CorrelationId(row.correlation_id), causation_id=CausationId(row.causation_id), event_type=row.event_type,
        schema_version=row.schema_version, payload=row.payload_json, occurred_at=row.created_at,
        status=OutboxStatus[row.status], attempts=row.attempts, available_at=row.available_at, published_at=row.published_at,
    )


class PostgresOutboxRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, record: OutboxRecord) -> None:
        with self._session_factory() as session:
            row = OutboxEventRow(
                id=record.outbox_id, aggregate_type=_outbox_aggregate_type(record.event_type),
                aggregate_id=record.workflow_instance_id.value, workflow_instance_id=record.workflow_instance_id.value,
                ticket_id=record.ticket_id.value, event_type=record.event_type, schema_version=record.schema_version,
                payload_json=record.payload, correlation_id=record.correlation_id.value, causation_id=record.causation_id.value,
                status=OutboxStatus.PENDING.name, attempts=0, available_at=record.occurred_at, published_at=None,
                created_at=record.occurred_at,
            )
            session.add(row)
            session.commit()

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        """08-transaction-and-outbox §"Outbox Publisher": "Scan by available_at."."""
        with self._session_factory() as session:
            stmt = (
                select(OutboxEventRow)
                .where(OutboxEventRow.status == OutboxStatus.PENDING.name, OutboxEventRow.available_at <= now)
                .order_by(OutboxEventRow.available_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_outbox_record(row) for row in rows]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__)
                .where(OutboxEventRow.id == outbox_id)
                .values(status=OutboxStatus.PUBLISHED.name, published_at=published_at)
            )
            session.commit()

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(attempts=attempts, available_at=next_available_at)
            )
            session.commit()

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._session_factory() as session:
            session.execute(update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(status=OutboxStatus.DEAD_LETTER.name))
            session.commit()

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        with self._session_factory() as session:
            stmt = (
                select(OutboxEventRow)
                .where(OutboxEventRow.status == OutboxStatus.DEAD_LETTER.name)
                .order_by(OutboxEventRow.created_at)
                .limit(limit)
            )
            rows = session.execute(stmt).scalars().all()
            return [_to_outbox_record(row) for row in rows]

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(
                    status=OutboxStatus.PENDING.name, attempts=0, available_at=available_at, published_at=None,
                )
            )
            session.commit()


class PostgresCommandIdempotencyRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        with self._session_factory() as session:
            row = session.get(CommandIdempotencyRow, str(idempotency_key))
            if row is None:
                return None
            return CommandIdempotencyRecord(
                idempotency_key=IdempotencyKey(row.idempotency_key), command_type=row.command_type,
                target_id=row.target_id, request_hash=row.request_hash, response_json=row.response_json,
                created_at=row.created_at, expires_at=row.expires_at,
            )

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        with self._session_factory() as session:
            try:
                session.execute(
                    CommandIdempotencyRow.__table__.insert().values(
                        idempotency_key=str(record.idempotency_key), command_type=record.command_type,
                        target_id=record.target_id, request_hash=record.request_hash, response_json=record.response_json,
                        created_at=record.created_at, expires_at=record.expires_at,
                    )
                )
                session.commit()
            except IntegrityError:
                # A concurrent caller already inserted this exact key first — whoever loses this
                # race must not overwrite the winner's stored response (that response is what
                # every future replay of this key must keep returning).
                session.rollback()
            return record
