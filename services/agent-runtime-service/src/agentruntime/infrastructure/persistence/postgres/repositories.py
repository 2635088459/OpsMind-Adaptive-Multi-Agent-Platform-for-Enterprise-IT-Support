"""SPEC-ARO-002/003: SQLAlchemy/Postgres-backed implementations of every
agentruntime.application.ports_out repository protocol. Each repository opens
one short-lived Session per call (`with self._session_factory() as session:`) —
real cross-repository transaction boundaries (e.g. "checkpoint and outbox commit
atomically", 08-transaction-and-outbox) land with a later spec once Runtime needs
to coordinate more than one aggregate write per request; every individual write
here is already atomic and safe under concurrent access (see save()'s docstrings).
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime

from sqlalchemy import desc, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from agentruntime.application.exceptions import AgentTaskVersionConflictException, WorkflowInstanceVersionConflictException
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    CommandIdempotencyRecord,
    OutboxRecord,
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
    CheckpointRow,
    CommandIdempotencyRow,
    OutboxEventRow,
    ProcessedEventRow,
    ToolRequestRow,
    WorkflowInstanceRow,
)

_PROCESSED_EVENT_CONSUMER_NAME = "agent-runtime-service"
_TERMINAL_TOOL_REQUEST_STATUSES = frozenset({ToolRequestStatus.COMPLETED, ToolRequestStatus.FAILED})


def _to_workflow_instance_record(row: WorkflowInstanceRow) -> WorkflowInstanceRecord:
    return WorkflowInstanceRecord(
        id=WorkflowInstanceId(row.id), ticket_id=TicketId(row.ticket_id), ticket_cycle_id=TicketCycleId(row.ticket_cycle_id),
        workflow_type=WorkflowType(row.workflow_type), definition_id=WorkflowDefinitionId(row.definition_id),
        definition_version=DefinitionVersion(row.definition_version), state=WorkflowState[row.state],
        workflow_version=row.workflow_version, pause_generation=row.pause_generation,
        created_at=row.created_at, updated_at=row.updated_at,
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
                            created_at=record.created_at, updated_at=record.updated_at,
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
                        pause_generation=record.pause_generation, updated_at=record.updated_at,
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
                            agent_role=record.agent_role,
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
                    )
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise AgentTaskVersionConflictException()
                session.commit()
            return record


def _to_checkpoint_record(row: CheckpointRow) -> CheckpointRecord:
    return CheckpointRecord(
        id=CheckpointId(row.id), workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id),
        type=CheckpointType[row.checkpoint_type], schema_version=row.payload_schema_version,
        payload=row.payload_json, recorded_at=row.created_at,
    )


class PostgresCheckpointRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, record: CheckpointRecord) -> CheckpointRecord:
        with self._session_factory() as session:
            row = CheckpointRow(
                id=record.id.value, workflow_instance_id=record.workflow_instance_id.value, workflow_version=None,
                checkpoint_type=record.type.name, cursor=None, payload_schema_version=record.schema_version,
                payload_json=record.payload, checksum=hashlib.sha256(record.payload.encode("utf-8")).hexdigest(),
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


class PostgresToolRequestRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, record: ToolRequestRecord) -> ToolRequestRecord:
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
            row.state = record.status.name
            row.input_payload_json = record.request_payload
            row.updated_at = record.updated_at
            row.completed_at = completed_at
            session.commit()
            return record

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequestRecord | None:
        with self._session_factory() as session:
            row = session.get(ToolRequestRow, tool_request_id.value)
            if row is None:
                return None
            return ToolRequestRecord(
                id=ToolRequestId(row.id), workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id),
                agent_task_id=AgentTaskId(row.agent_task_id), preceding_checkpoint_id=CheckpointId(row.preceding_checkpoint_id),
                tool_name=row.tool_name, request_payload=row.input_payload_json, status=ToolRequestStatus[row.state],
                created_at=row.created_at, updated_at=row.updated_at,
            )


class PostgresProcessedEventRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def is_processed(self, event_id: str) -> bool:
        with self._session_factory() as session:
            row = session.get(ProcessedEventRow, (event_id, _PROCESSED_EVENT_CONSUMER_NAME))
            return row is not None

    def mark_processed(
        self,
        event_id: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None:
        with self._session_factory() as session:
            existing = session.get(ProcessedEventRow, (event_id, _PROCESSED_EVENT_CONSUMER_NAME))
            if existing is not None:
                return
            row = ProcessedEventRow(
                event_id=event_id, consumer_name=_PROCESSED_EVENT_CONSUMER_NAME, event_type=event_type,
                workflow_instance_id=workflow_instance_id.value if workflow_instance_id else None, processed_at=processed_at,
            )
            session.add(row)
            try:
                session.commit()
            except IntegrityError:
                # Two consumers racing to mark the same event_id: whichever loses is a no-op, not an error
                # (02-business-invariants: duplicate events must not advance Workflow again).
                session.rollback()


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
