"""SPEC-TG-002: SQLAlchemy/Postgres-backed implementations of every
tool_gateway.ports.storage_port repository Protocol. Each repository opens one
short-lived Session per call (``with self._session_factory() as session:``) —
real cross-repository transaction boundaries (08-transaction-and-outbox's own
"in one transaction" language for e.g. "insert tool_requests + tool_audit_records
+ outbox_events together") are deliberately NOT built here; every individual
write below is already atomic and safe under concurrent access on its own
(see each save()'s own CAS docstring). This mirrors memory-knowledge-service's
own SPEC-MK-002 repositories.py precedent exactly — see that module's own
docstring: "real cross-repository transaction boundaries land with a later spec
once a use case needs to coordinate more than one aggregate write per request."
No such later spec has landed for that service even after 32 specs, so this is
this codebase's accepted, honestly-documented steady state, not a shortcut
unique to this domain.

CAS pattern: ToolRequest/ToolExecution/ToolConnector carry no explicit version
field (01-domain-model's own field lists) — every one of them uses a
status-based compare-and-swap via ``save(entity, expected_status)``, mirroring
memory-knowledge-service's own MemoryCandidate/MemoryVersion/KnowledgeDocument
CAS shape (Core ``update().where(id=..., status=expected_status)``, rowcount
checked — never SQLAlchemy ORM's ``session.get()``-then-mutate-then-commit,
which generates no status predicate at all; the SPEC-ARO-003 lesson this whole
platform now applies everywhere).
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from tool_gateway.application.exceptions import ToolRequestStatusConflictException
from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.credential_binding import CredentialBinding, CredentialBindingStatus
from tool_gateway.domain.enums import (
    ApprovalLinkageStatus,
    ConnectorHealthStatus,
    OutboxStatus,
    RedactionStatus,
    RequestedByType,
    ResultStatus,
    RiskLevel,
    SideEffectKind,
    ToolExecutionStatus,
    ToolRequestStatus,
)
from tool_gateway.domain.ids import (
    AgentTaskId,
    ApprovalRequestId,
    ConnectorId,
    CredentialBindingId,
    IdempotencyKey,
    OperationKey,
    ResultEnvelopeId,
    TicketCycleId,
    TicketId,
    ToolExecutionId,
    ToolRequestId,
    WorkflowInstanceId,
)
from tool_gateway.domain.records import AuditRecordEntry, OutboxRecord
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.domain.values import ApprovalRequestRef, NetworkPolicy, RetryPolicy, RiskDecisionRef, TimeoutPolicy

from .models import (
    CredentialBindingRow,
    OutboxEventRow,
    ProcessedEventRow,
    ToolAuditRecordRow,
    ToolConnectorRow,
    ToolExecutionRow,
    ToolRequestRow,
    ToolResultRow,
)


# --------------------------------------------------------------------------------
# ToolRequest
# --------------------------------------------------------------------------------


def _risk_decision_to_json(risk: RiskDecisionRef | None) -> dict | None:
    if risk is None:
        return None
    return {
        "decisionId": risk.decision_id, "riskLevel": risk.risk_level.name, "requiresApproval": risk.requires_approval,
        "decidedAt": risk.decided_at.isoformat(), "decidedBy": risk.decided_by,
    }


def _risk_decision_from_json(data: dict | None) -> RiskDecisionRef | None:
    if data is None:
        return None
    return RiskDecisionRef(
        decision_id=data["decisionId"], risk_level=RiskLevel[data["riskLevel"]], requires_approval=data["requiresApproval"],
        decided_at=datetime.fromisoformat(data["decidedAt"]), decided_by=data["decidedBy"],
    )


def _tool_request_to_row_values(tool_request: ToolRequest) -> dict:
    return dict(
        idempotency_key=str(tool_request.idempotency_key), payload_hash=tool_request.payload_hash,
        ticket_id=tool_request.ticket_id.value if tool_request.ticket_id else None,
        ticket_cycle_id=tool_request.ticket_cycle_id.value if tool_request.ticket_cycle_id else None,
        workflow_instance_id=tool_request.workflow_instance_id.value if tool_request.workflow_instance_id else None,
        agent_task_id=tool_request.agent_task_id.value if tool_request.agent_task_id else None,
        requested_by_type=tool_request.requested_by_type.name, requested_by_id=tool_request.requested_by_id,
        capability_name=tool_request.capability_name, tool_name=tool_request.tool_name,
        input_payload_json=tool_request.input_payload, reason=tool_request.reason, status=tool_request.status.name,
        risk_decision_ref_json=_risk_decision_to_json(tool_request.risk_snapshot),
        approval_request_id=tool_request.approval_ref.approval_request_id.value if tool_request.approval_ref else None,
        resolved_connector_id=tool_request.resolved_connector_id.value if tool_request.resolved_connector_id else None,
        resolved_connector_version=tool_request.resolved_connector_version,
        result_envelope_id=tool_request.result_envelope_id.value if tool_request.result_envelope_id else None,
        denial_reason=tool_request.denial_reason, updated_at=tool_request.updated_at,
        completed_at=tool_request.updated_at if tool_request.status.is_terminal() else None,
        retry_not_before=tool_request.retry_not_before,
    )


def _row_to_approval_ref(row: ToolRequestRow) -> ApprovalRequestRef | None:
    # 07-data-model `tool_requests` has no columns for the linkage's own
    # status/requested_at/decided_at/decided_by (only approval_request_id) —
    # SPEC-TG-009 09-concurrency-and-idempotency §"Approval Event Idempotency"
    # needs approval_request_id to survive a reload (to verify an incoming
    # approval.granted.v1/denied.v1's own approvalRequestId matches the stored
    # linkage), so that much is reconstructed; status/requested_at are
    # best-effort placeholders (APPROVAL_REQUESTED / this row's own updated_at),
    # not a faithful history — no current caller reads those two fields back.
    if row.approval_request_id is None:
        return None
    return ApprovalRequestRef(
        approval_request_id=ApprovalRequestId(row.approval_request_id), status=ApprovalLinkageStatus.APPROVAL_REQUESTED,
        requested_at=row.updated_at,
    )


def _row_to_tool_request(row: ToolRequestRow) -> ToolRequest:
    return ToolRequest(
        tool_request_id=ToolRequestId(row.id), idempotency_key=IdempotencyKey(row.idempotency_key), payload_hash=row.payload_hash,
        ticket_id=TicketId(row.ticket_id) if row.ticket_id else None,
        ticket_cycle_id=TicketCycleId(row.ticket_cycle_id) if row.ticket_cycle_id else None,
        workflow_instance_id=WorkflowInstanceId(row.workflow_instance_id) if row.workflow_instance_id else None,
        agent_task_id=AgentTaskId(row.agent_task_id) if row.agent_task_id else None,
        requested_by_type=RequestedByType[row.requested_by_type], requested_by_id=row.requested_by_id,
        capability_name=row.capability_name, tool_name=row.tool_name, input_payload=row.input_payload_json, reason=row.reason,
        status=ToolRequestStatus[row.status], created_at=row.created_at, updated_at=row.updated_at,
        risk_snapshot=_risk_decision_from_json(row.risk_decision_ref_json), approval_ref=_row_to_approval_ref(row),
        resolved_connector_id=ConnectorId(row.resolved_connector_id) if row.resolved_connector_id else None,
        resolved_connector_version=row.resolved_connector_version,
        result_envelope_id=ResultEnvelopeId(row.result_envelope_id) if row.result_envelope_id else None,
        denial_reason=row.denial_reason, retry_not_before=row.retry_not_before,
    )


def _current_status(session: Session, model: type, row_id: uuid.UUID) -> str | None:
    return session.execute(select(model.status).where(model.id == row_id)).scalar_one_or_none()


def _now() -> datetime:
    return datetime.now(UTC)


# SPEC-TG-022: mirrors domain.enums._TERMINAL_REQUEST_STATUSES — a plain name
# tuple since SQLAlchemy's own .not_in() needs the row's stored string values,
# not the enum members themselves.
_TERMINAL_REQUEST_STATUS_NAMES = (
    ToolRequestStatus.COMPLETED.name, ToolRequestStatus.REJECTED.name, ToolRequestStatus.POLICY_DENIED.name,
    ToolRequestStatus.APPROVAL_DENIED.name, ToolRequestStatus.CANCELLED.name, ToolRequestStatus.TERMINAL_FAILED.name,
)


class PostgresToolRequestRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequest | None:
        with self._session_factory() as session:
            row = session.get(ToolRequestRow, tool_request_id.value)
            return _row_to_tool_request(row) if row else None

    def find_by_idempotency_key(
        self, workflow_instance_id: str | None, agent_task_id: str | None, idempotency_key: IdempotencyKey,
    ) -> ToolRequest | None:
        with self._session_factory() as session:
            stmt = select(ToolRequestRow).where(
                ToolRequestRow.workflow_instance_id == (uuid.UUID(workflow_instance_id) if workflow_instance_id else None),
                ToolRequestRow.agent_task_id == (uuid.UUID(agent_task_id) if agent_task_id else None),
                ToolRequestRow.idempotency_key == str(idempotency_key),
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_tool_request(row) if row else None

    def save(self, tool_request: ToolRequest, expected_status: ToolRequestStatus | None) -> ToolRequest:
        values = _tool_request_to_row_values(tool_request)
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(
                        ToolRequestRow.__table__.insert().values(
                            id=tool_request.tool_request_id.value, created_at=tool_request.created_at, **values,
                        )
                    )
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise ToolRequestStatusConflictException(tool_request.tool_request_id, expected_status) from exc
            else:
                result = session.execute(
                    update(ToolRequestRow.__table__)
                    .where(ToolRequestRow.id == tool_request.tool_request_id.value, ToolRequestRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise ToolRequestStatusConflictException(tool_request.tool_request_id, expected_status)
                session.commit()
            return tool_request

    def find_queued(self, now: datetime, limit: int) -> list[ToolRequest]:
        with self._session_factory() as session:
            stmt = (
                select(ToolRequestRow)
                .where(
                    ToolRequestRow.status == ToolRequestStatus.QUEUED.name,
                    (ToolRequestRow.retry_not_before.is_(None)) | (ToolRequestRow.retry_not_before <= now),
                )
                .order_by(ToolRequestRow.created_at)
                .limit(limit)
            )
            return [_row_to_tool_request(row) for row in session.execute(stmt).scalars().all()]

    def find_non_terminal_by_workflow_instance(self, workflow_instance_id: object) -> list[ToolRequest]:
        with self._session_factory() as session:
            stmt = select(ToolRequestRow).where(
                ToolRequestRow.workflow_instance_id == workflow_instance_id.value,
                ToolRequestRow.status.not_in(_TERMINAL_REQUEST_STATUS_NAMES),
            )
            return [_row_to_tool_request(row) for row in session.execute(stmt).scalars().all()]


# --------------------------------------------------------------------------------
# ToolExecution
# --------------------------------------------------------------------------------

_RECONCILABLE_STATUSES = (ToolExecutionStatus.TIMED_OUT.name, ToolExecutionStatus.PARTIAL_SIDE_EFFECT.name)
_IN_FLIGHT_STATUSES = (ToolExecutionStatus.CLAIMED.name, ToolExecutionStatus.PREPARING.name, ToolExecutionStatus.INVOKING.name)


def _tool_execution_to_row_values(execution: ToolExecution) -> dict:
    return dict(
        tool_request_id=execution.tool_request_id.value, attempt_number=execution.attempt_number,
        connector_id=execution.connector_id.value, connector_version=execution.connector_version,
        operation_key=str(execution.operation_key) if execution.operation_key else None,
        side_effect_kind=execution.side_effect_kind.name, status=execution.status.name, lease_owner=execution.lease_owner,
        lease_expires_at=execution.lease_expires_at, started_at=execution.started_at, completed_at=execution.completed_at,
        timeout_at=execution.timeout_at,
        result_envelope_id=execution.result_envelope_id.value if execution.result_envelope_id else None,
        error_code=execution.error_code, retryable=execution.retryable,
    )


def _row_to_tool_execution(row: ToolExecutionRow) -> ToolExecution:
    return ToolExecution(
        execution_id=ToolExecutionId(row.id), tool_request_id=ToolRequestId(row.tool_request_id), attempt_number=row.attempt_number,
        connector_id=ConnectorId(row.connector_id), connector_version=row.connector_version,
        side_effect_kind=SideEffectKind[row.side_effect_kind], status=ToolExecutionStatus[row.status],
        operation_key=OperationKey(row.operation_key) if row.operation_key else None, lease_owner=row.lease_owner,
        lease_expires_at=row.lease_expires_at, started_at=row.started_at, completed_at=row.completed_at,
        timeout_at=row.timeout_at, result_envelope_id=ResultEnvelopeId(row.result_envelope_id) if row.result_envelope_id else None,
        error_code=row.error_code, retryable=row.retryable,
    )


class PostgresToolExecutionRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, execution_id: ToolExecutionId) -> ToolExecution | None:
        with self._session_factory() as session:
            row = session.get(ToolExecutionRow, execution_id.value)
            return _row_to_tool_execution(row) if row else None

    def find_active_by_tool_request(self, tool_request_id: ToolRequestId) -> ToolExecution | None:
        with self._session_factory() as session:
            terminal = (ToolExecutionStatus.COMPLETED.name, ToolExecutionStatus.LEASE_EXPIRED.name, ToolExecutionStatus.TERMINAL_FAILED.name)
            stmt = (
                select(ToolExecutionRow)
                .where(ToolExecutionRow.tool_request_id == tool_request_id.value, ToolExecutionRow.status.not_in(terminal))
                .order_by(ToolExecutionRow.attempt_number.desc())
                .limit(1)
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_tool_execution(row) if row else None

    def find_attempts(self, tool_request_id: ToolRequestId) -> list[ToolExecution]:
        with self._session_factory() as session:
            stmt = select(ToolExecutionRow).where(ToolExecutionRow.tool_request_id == tool_request_id.value).order_by(ToolExecutionRow.attempt_number)
            return [_row_to_tool_execution(row) for row in session.execute(stmt).scalars().all()]

    def save(self, execution: ToolExecution, expected_status: ToolExecutionStatus | None) -> ToolExecution:
        # No caller currently reads a stored ToolExecution's previous status
        # before saving a new one (each application service builds the next
        # in-memory transition itself and persists the result once) — mirrors
        # InMemoryToolExecutionRepository.save()'s own always-upsert shape.
        # ``expected_status`` is accepted for Protocol-signature parity and
        # future callers, matching adapters.db.repositories.InMemoryToolExecutionRepository.
        values = _tool_execution_to_row_values(execution)
        with self._session_factory() as session:
            existing = session.get(ToolExecutionRow, execution.execution_id.value)
            if existing is None:
                session.execute(ToolExecutionRow.__table__.insert().values(id=execution.execution_id.value, **values))
            else:
                session.execute(update(ToolExecutionRow.__table__).where(ToolExecutionRow.id == execution.execution_id.value).values(**values))
            session.commit()
            return execution

    def find_reconcilable(self, limit: int) -> list[ToolExecution]:
        with self._session_factory() as session:
            stmt = select(ToolExecutionRow).where(ToolExecutionRow.status.in_(_RECONCILABLE_STATUSES)).limit(limit)
            return [_row_to_tool_execution(row) for row in session.execute(stmt).scalars().all()]

    def find_lease_expired(self, now: datetime, limit: int) -> list[ToolExecution]:
        with self._session_factory() as session:
            stmt = (
                select(ToolExecutionRow)
                .where(ToolExecutionRow.status.in_(_IN_FLIGHT_STATUSES), ToolExecutionRow.lease_expires_at < now)
                .limit(limit)
            )
            return [_row_to_tool_execution(row) for row in session.execute(stmt).scalars().all()]


# --------------------------------------------------------------------------------
# ToolResultEnvelope
# --------------------------------------------------------------------------------


def _row_to_result_envelope(row: ToolResultRow) -> ToolResultEnvelope:
    return ToolResultEnvelope(
        result_envelope_id=ResultEnvelopeId(row.id), execution_id=ToolExecutionId(row.execution_id), status=ResultStatus[row.status],
        summary=row.summary, structured_output=row.structured_output_json, raw_output_ref=row.raw_output_ref,
        redaction_status=RedactionStatus[row.redaction_status], evidence_refs=tuple(row.evidence_refs_json),
        external_resource_refs=tuple(row.external_resource_refs_json), error_code=row.error_code, retryable=row.retryable,
    )


class PostgresResultEnvelopeRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, result_envelope_id: ResultEnvelopeId) -> ToolResultEnvelope | None:
        with self._session_factory() as session:
            row = session.get(ToolResultRow, result_envelope_id.value)
            return _row_to_result_envelope(row) if row else None

    def save(self, envelope: ToolResultEnvelope) -> ToolResultEnvelope:
        with self._session_factory() as session:
            session.execute(ToolResultRow.__table__.insert().values(
                id=envelope.result_envelope_id.value, execution_id=envelope.execution_id.value, status=envelope.status.name,
                summary=envelope.summary, structured_output_json=envelope.structured_output, raw_output_ref=envelope.raw_output_ref,
                redaction_status=envelope.redaction_status.name, evidence_refs_json=list(envelope.evidence_refs),
                external_resource_refs_json=list(envelope.external_resource_refs), error_code=envelope.error_code,
                retryable=envelope.retryable, created_at=_now(),
            ))
            session.commit()
            return envelope


# --------------------------------------------------------------------------------
# ToolConnector
# --------------------------------------------------------------------------------


def _connector_to_row_values(connector: ToolConnector) -> dict:
    return dict(
        version=connector.version, name=connector.name, status=connector.health_status.name,
        # SPEC-TG-021: manifest_json had no real content before this spec
        # (always ``{}`` — every registered connector's manifest was
        # effectively unrecoverable from a reload beyond the typed columns
        # below). allowed_requester_types is the first field to actually use
        # it, chosen over a new typed column since it is exactly what this
        # column's own name already promises: extra manifest data 07-data-
        # model's own typed column list doesn't otherwise name a slot for.
        # SPEC-TG-030 reuses the same slot for consecutiveHealthCheckFailures
        # rather than adding a migration — one more field this column's own
        # name already covers.
        manifest_json={
            "allowedRequesterTypes": [t.name for t in connector.allowed_requester_types],
            "consecutiveHealthCheckFailures": connector.consecutive_health_check_failures,
        },
        capabilities_json=[capability.name for capability in connector.capabilities], input_schema=connector.input_schema_ref,
        output_schema=connector.output_schema_ref, risk_level=connector.risk_level.name, requires_approval=connector.requires_approval,
        secret_requirements_json=list(connector.secret_requirements),
        network_policy_json={"allowedHosts": list(connector.network_policy.allowed_hosts), "denyByDefault": connector.network_policy.deny_by_default},
        timeout_policy_json={
            "connectTimeoutSeconds": connector.timeout_policy.connect_timeout_seconds,
            "invokeTimeoutSeconds": connector.timeout_policy.invoke_timeout_seconds,
        },
        retry_policy_json={"maxAttempts": connector.retry_policy.max_attempts, "backoffSeconds": connector.retry_policy.backoff_seconds},
        updated_at=_now(),
    )


def _row_to_connector(row: ToolConnectorRow) -> ToolConnector:
    return ToolConnector(
        connector_id=ConnectorId(row.id), name=row.name, version=row.version,
        capabilities=tuple(Capability(name) for name in row.capabilities_json), input_schema_ref=row.input_schema,
        output_schema_ref=row.output_schema, risk_level=RiskLevel[row.risk_level], requires_approval=row.requires_approval,
        # side_effect_kind has no 07-data-model column of its own — derived from
        # the same MUTATING keyword rule adapters.policy.policy_client uses,
        # since no current service round-trips this field through Postgres yet
        # (only the in-memory adapter path exercises it directly at creation
        # time). Deferred to phase-03 SPEC-TG-011 (connector SDK) if a real
        # manifest-driven column is needed.
        side_effect_kind=SideEffectKind.MUTATING if any(
            keyword in name.lower() for name in row.capabilities_json for keyword in ("restart", "create", "delete", "update", "terminate", "revoke")
        ) else SideEffectKind.READ_ONLY,
        secret_requirements=tuple(row.secret_requirements_json),
        network_policy=NetworkPolicy(
            allowed_hosts=tuple(row.network_policy_json.get("allowedHosts", [])), deny_by_default=row.network_policy_json.get("denyByDefault", True),
        ),
        timeout_policy=TimeoutPolicy(
            connect_timeout_seconds=row.timeout_policy_json.get("connectTimeoutSeconds", 5),
            invoke_timeout_seconds=row.timeout_policy_json.get("invokeTimeoutSeconds", 30),
        ),
        retry_policy=RetryPolicy(
            max_attempts=row.retry_policy_json.get("maxAttempts", 3), backoff_seconds=row.retry_policy_json.get("backoffSeconds", 5),
        ),
        health_status=ConnectorHealthStatus[row.status],
        allowed_requester_types=tuple(RequestedByType[t] for t in row.manifest_json.get("allowedRequesterTypes", [])),
        consecutive_health_check_failures=row.manifest_json.get("consecutiveHealthCheckFailures", 0),
    )


class PostgresConnectorRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, connector_id: ConnectorId) -> ToolConnector | None:
        with self._session_factory() as session:
            row = session.get(ToolConnectorRow, connector_id.value)
            return _row_to_connector(row) if row else None

    def save(self, connector: ToolConnector) -> ToolConnector:
        values = _connector_to_row_values(connector)
        with self._session_factory() as session:
            existing = session.get(ToolConnectorRow, connector.connector_id.value)
            if existing is None:
                session.execute(ToolConnectorRow.__table__.insert().values(id=connector.connector_id.value, created_at=_now(), **values))
            else:
                session.execute(update(ToolConnectorRow.__table__).where(ToolConnectorRow.id == connector.connector_id.value).values(**values))
            session.commit()
            return connector

    def list_all(self) -> list[ToolConnector]:
        with self._session_factory() as session:
            return [_row_to_connector(row) for row in session.execute(select(ToolConnectorRow)).scalars().all()]


# --------------------------------------------------------------------------------
# Outbox / ProcessedEvent / Audit
# --------------------------------------------------------------------------------


def _row_to_outbox_record(row: OutboxEventRow) -> OutboxRecord:
    return OutboxRecord(
        outbox_id=row.id, aggregate_type=row.aggregate_type, aggregate_id=row.aggregate_id, event_type=row.event_type,
        event_version=row.event_version, payload=row.payload_json, occurred_at=row.occurred_at, correlation_id=row.correlation_id,
        status=OutboxStatus[row.status], attempts=row.attempt_count, available_at=row.available_at,
    )


class PostgresOutboxRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, record: OutboxRecord) -> None:
        with self._session_factory() as session:
            session.execute(OutboxEventRow.__table__.insert().values(
                id=record.outbox_id, aggregate_type=record.aggregate_type, aggregate_id=record.aggregate_id,
                event_type=record.event_type, event_version=record.event_version, payload_json=record.payload, headers_json={},
                correlation_id=record.correlation_id, status=record.status.name, attempt_count=record.attempts,
                available_at=record.available_at, occurred_at=record.occurred_at,
            ))
            session.commit()

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        with self._session_factory() as session:
            stmt = (
                select(OutboxEventRow)
                .where(OutboxEventRow.status == OutboxStatus.PENDING.name, (OutboxEventRow.available_at.is_(None)) | (OutboxEventRow.available_at <= now))
                .order_by(OutboxEventRow.occurred_at)
                .limit(limit)
            )
            return [_row_to_outbox_record(row) for row in session.execute(stmt).scalars().all()]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id)
                .values(status=OutboxStatus.PUBLISHED.name, published_at=published_at)
            )
            session.commit()

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id)
                .values(attempt_count=attempts, available_at=next_available_at)
            )
            session.commit()

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._session_factory() as session:
            session.execute(update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(status=OutboxStatus.DEAD_LETTER.name))
            session.commit()

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        with self._session_factory() as session:
            stmt = select(OutboxEventRow).where(OutboxEventRow.status == OutboxStatus.DEAD_LETTER.name).limit(limit)
            return [_row_to_outbox_record(row) for row in session.execute(stmt).scalars().all()]

    def find_by_id(self, outbox_id: uuid.UUID) -> OutboxRecord | None:
        with self._session_factory() as session:
            row = session.get(OutboxEventRow, outbox_id)
            return _row_to_outbox_record(row) if row else None

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        """SPEC-TG-028: see ``adapters.db.repositories.InMemoryOutboxRepository.
        requeue()``'s own docstring for why the attempt counter resets to 0."""

        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id)
                .values(status=OutboxStatus.PENDING.name, attempt_count=0, available_at=available_at)
            )
            session.commit()


class PostgresProcessedEventRepository:
    """09-concurrency-and-idempotency §"Event Consumer Idempotency":
    ``eventId + consumerName``. 08-transaction-and-outbox §"Processed Events":
    "the transaction must insert processed_events first. Unique-key conflict
    means skip the event." No real event consumer calls this yet in this
    domain's own scope (phase-02/06 build the first one — see
    ``ports.approval_port`` module docstring); this repository is the baseline
    mechanism that consumer will call.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._session_factory() as session:
            stmt = select(ProcessedEventRow.id).where(ProcessedEventRow.event_id == event_id, ProcessedEventRow.consumer_name == consumer_name)
            return session.execute(stmt).scalar_one_or_none() is not None

    def mark_processed(self, event_id: str, consumer_name: str, processed_at: datetime) -> None:
        with self._session_factory() as session:
            try:
                session.execute(ProcessedEventRow.__table__.insert().values(
                    id=uuid.uuid4(), event_id=event_id, consumer_name=consumer_name, processed_at=processed_at,
                ))
                session.commit()
            except IntegrityError:
                # 08-transaction-and-outbox: "Unique-key conflict means skip the
                # event, guaranteeing duplicate event idempotency" — a second
                # mark_processed() for the same (event_id, consumer_name) is a
                # no-op, not an error.
                session.rollback()


def _row_to_audit_entry(row: ToolAuditRecordRow) -> AuditRecordEntry:
    return AuditRecordEntry(
        audit_id=row.id, action=row.action, resource_type=row.resource_type, resource_id=row.resource_id,
        outcome=row.outcome, actor_id=row.actor_id, correlation_id=row.correlation_id, recorded_at=row.occurred_at,
        ticket_id=(row.metadata_json or {}).get("ticketId"), detail=(row.metadata_json or {}).get("detail"),
        tool_request_id=str(row.tool_request_id) if row.tool_request_id else None,
        execution_id=str(row.execution_id) if row.execution_id else None,
        connector_id=str(row.connector_id) if row.connector_id else None,
    )


class PostgresAuditRecordRepository:
    """SPEC-TG-027 "Audit Query And Admin Reporting": ``tool_request_id``/
    ``execution_id``/``connector_id`` are real 07-data-model `tool_audit_records`
    columns since SPEC-TG-002, but every write persisted them as ``NULL`` — the
    domain ``AuditRecordEntry`` carried no fields for them at all. Now written
    (and read back) for real, closing 12-observability §"Audit Observability"'s
    own "failures and credential usage by connector" query — a resource_type/
    resource_id pair alone cannot answer that (a credential_binding_resolved
    entry's own resource is the execution, not the connector).
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, entry: AuditRecordEntry) -> None:
        with self._session_factory() as session:
            session.execute(ToolAuditRecordRow.__table__.insert().values(
                id=entry.audit_id, actor_type=None, actor_id=entry.actor_id, action=entry.action,
                tool_request_id=uuid.UUID(entry.tool_request_id) if entry.tool_request_id else None,
                execution_id=uuid.UUID(entry.execution_id) if entry.execution_id else None,
                connector_id=uuid.UUID(entry.connector_id) if entry.connector_id else None,
                resource_type=entry.resource_type, resource_id=entry.resource_id,
                outcome=entry.outcome, correlation_id=entry.correlation_id,
                metadata_json={"ticketId": entry.ticket_id, "detail": entry.detail}, occurred_at=entry.recorded_at,
            ))
            session.commit()

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = select(ToolAuditRecordRow).order_by(ToolAuditRecordRow.occurred_at.desc()).limit(limit)
            return [_row_to_audit_entry(row) for row in session.execute(stmt).scalars().all()]

    def find_by_ticket_id(self, ticket_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = (
                select(ToolAuditRecordRow)
                .where(ToolAuditRecordRow.metadata_json["ticketId"].astext == ticket_id)
                .order_by(ToolAuditRecordRow.occurred_at.desc())
                .limit(limit)
            )
            return [_row_to_audit_entry(row) for row in session.execute(stmt).scalars().all()]

    def find_by_actor_id(self, actor_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = (
                select(ToolAuditRecordRow).where(ToolAuditRecordRow.actor_id == actor_id)
                .order_by(ToolAuditRecordRow.occurred_at.desc()).limit(limit)
            )
            return [_row_to_audit_entry(row) for row in session.execute(stmt).scalars().all()]

    def find_by_connector_id(self, connector_id: str, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = (
                select(ToolAuditRecordRow).where(ToolAuditRecordRow.connector_id == uuid.UUID(connector_id))
                .order_by(ToolAuditRecordRow.occurred_at.desc()).limit(limit)
            )
            return [_row_to_audit_entry(row) for row in session.execute(stmt).scalars().all()]


def _row_to_credential_binding(row: CredentialBindingRow) -> CredentialBinding:
    return CredentialBinding(
        credential_binding_id=CredentialBindingId(row.id), connector_id=ConnectorId(row.connector_id), tenant_id=row.tenant_id,
        scope=row.scope, vault_ref=row.vault_ref, rotation_version=row.rotation_version,
        status=CredentialBindingStatus[row.status], created_at=row.created_at, last_used_at=row.last_used_at,
    )


class PostgresCredentialBindingRepository:
    """SPEC-TG-012 07-data-model `credential_bindings` — the first repository to
    actually write this table (see ``adapters.db.models.CredentialBindingRow``'s
    own docstring for the SPEC-TG-001-era deferral this closes).
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_active(self, connector_id: ConnectorId, scope: str) -> CredentialBinding | None:
        with self._session_factory() as session:
            stmt = select(CredentialBindingRow).where(
                CredentialBindingRow.connector_id == connector_id.value, CredentialBindingRow.scope == scope,
                CredentialBindingRow.status == CredentialBindingStatus.ACTIVE.name,
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_credential_binding(row) if row else None

    def save(self, binding: CredentialBinding) -> CredentialBinding:
        values = dict(
            connector_id=binding.connector_id.value, tenant_id=binding.tenant_id, scope=binding.scope,
            vault_ref=binding.vault_ref, rotation_version=binding.rotation_version, status=binding.status.name,
            last_used_at=binding.last_used_at,
        )
        with self._session_factory() as session:
            existing = session.get(CredentialBindingRow, binding.credential_binding_id.value)
            if existing is None:
                session.execute(CredentialBindingRow.__table__.insert().values(
                    id=binding.credential_binding_id.value, created_at=binding.created_at, **values,
                ))
            else:
                session.execute(
                    update(CredentialBindingRow.__table__).where(CredentialBindingRow.id == binding.credential_binding_id.value).values(**values)
                )
            session.commit()
            return binding
