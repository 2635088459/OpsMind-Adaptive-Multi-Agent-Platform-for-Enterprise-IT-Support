"""Read-model DTOs returned by application services to the interfaces layer —
kept distinct from the domain aggregates themselves so ``tool_gateway.api``
never has to import ``tool_gateway.domain`` types directly, mirroring
memory-knowledge-service's own ``application/views.py`` convention.
"""

from __future__ import annotations

from dataclasses import dataclass

from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.records import AuditRecordEntry, OutboxRecord
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_request import ToolRequest


@dataclass(frozen=True, slots=True)
class ToolRequestView:
    """05-api-contracts §"Runtime API": the ``POST /tool-requests`` accept
    response names ``requiresApproval``/``approvalRequestId`` explicitly, on top
    of the ``status``/``toolRequestId`` fields this view already carried — added
    below rather than split into a separate, narrower accept-response model,
    since GET's own "ToolRequest summary" already needs a superset of those
    same fields (mirrors memory-knowledge-service's own single-view-per-
    aggregate convention over one response model per endpoint).
    """

    tool_request_id: str
    status: str
    capability_name: str
    tool_name: str | None
    requested_by_type: str
    requested_by_id: str
    reason: str
    requires_approval: bool
    approval_request_id: str | None
    denial_reason: str | None
    result_envelope_id: str | None
    created_at: str
    updated_at: str

    @staticmethod
    def from_domain(tool_request: ToolRequest) -> "ToolRequestView":
        return ToolRequestView(
            tool_request_id=str(tool_request.tool_request_id), status=tool_request.status.name,
            capability_name=tool_request.capability_name, tool_name=tool_request.tool_name,
            requested_by_type=tool_request.requested_by_type.name, requested_by_id=tool_request.requested_by_id,
            reason=tool_request.reason,
            requires_approval=tool_request.risk_snapshot.requires_approval if tool_request.risk_snapshot else False,
            approval_request_id=(
                str(tool_request.approval_ref.approval_request_id) if tool_request.approval_ref else None
            ),
            denial_reason=tool_request.denial_reason,
            result_envelope_id=str(tool_request.result_envelope_id) if tool_request.result_envelope_id else None,
            created_at=tool_request.created_at.isoformat(), updated_at=tool_request.updated_at.isoformat(),
        )


@dataclass(frozen=True, slots=True)
class ToolResultView:
    """INV-TG-007: carries ``raw_output_ref`` (a storage reference), never raw
    output content.
    """

    result_envelope_id: str
    execution_id: str
    status: str
    summary: str
    structured_output: dict
    raw_output_ref: str | None
    redaction_status: str
    evidence_refs: tuple[str, ...]
    error_code: str | None
    retryable: bool

    @staticmethod
    def from_domain(envelope: ToolResultEnvelope) -> "ToolResultView":
        return ToolResultView(
            result_envelope_id=str(envelope.result_envelope_id), execution_id=str(envelope.execution_id),
            status=envelope.status.name, summary=envelope.summary, structured_output=envelope.structured_output,
            raw_output_ref=envelope.raw_output_ref, redaction_status=envelope.redaction_status.name,
            evidence_refs=envelope.evidence_refs, error_code=envelope.error_code, retryable=envelope.retryable,
        )


@dataclass(frozen=True, slots=True)
class RawOutputView:
    """SPEC-TG-020 05-api-contracts §"Result API": ``GET /tool-results/
    {resultEnvelopeId}/raw`` — deliberately its own, narrower view rather than
    a field added to ``ToolResultView``: raw content must never appear on the
    normal result read path, only this dedicated, privileged one.
    ``raw_output`` is ``None`` when the connector never produced a
    ``raw_output_ref`` in the first place (a legitimate fact, not a denial).
    """

    result_envelope_id: str
    raw_output: str | None


@dataclass(frozen=True, slots=True)
class ConnectorView:
    connector_id: str
    name: str
    version: str
    capabilities: tuple[str, ...]
    risk_level: str
    requires_approval: bool
    health_status: str
    # SPEC-TG-029 "Connector Admin Lifecycle API": the full manifest an admin
    # actually registered — the summary fields above (name/version/
    # capabilities/risk/health) gave no visibility into what was really
    # configured (secrets required, network allowlist, timeouts, retry
    # policy, requester-type restriction).
    side_effect_kind: str = "READ_ONLY"
    secret_requirements: tuple[str, ...] = ()
    allowed_hosts: tuple[str, ...] = ()
    deny_by_default: bool = True
    connect_timeout_seconds: int = 5
    invoke_timeout_seconds: int = 30
    max_attempts: int = 3
    backoff_seconds: int = 5
    allowed_requester_types: tuple[str, ...] = ()
    # SPEC-TG-030 "Crash Recovery Backpressure Scaling": admin visibility into
    # how close an ACTIVE/DEGRADED connector is to the automatic DEGRADED/
    # DISABLED escalation threshold (see domain.connector.ToolConnector's own
    # ``consecutive_health_check_failures`` field docstring).
    consecutive_health_check_failures: int = 0

    @staticmethod
    def from_domain(connector: ToolConnector) -> "ConnectorView":
        return ConnectorView(
            connector_id=str(connector.connector_id), name=connector.name, version=connector.version,
            capabilities=tuple(capability.name for capability in connector.capabilities),
            risk_level=connector.risk_level.name, requires_approval=connector.requires_approval,
            health_status=connector.health_status.name, side_effect_kind=connector.side_effect_kind.name,
            secret_requirements=connector.secret_requirements, allowed_hosts=connector.network_policy.allowed_hosts,
            deny_by_default=connector.network_policy.deny_by_default,
            connect_timeout_seconds=connector.timeout_policy.connect_timeout_seconds,
            invoke_timeout_seconds=connector.timeout_policy.invoke_timeout_seconds,
            max_attempts=connector.retry_policy.max_attempts, backoff_seconds=connector.retry_policy.backoff_seconds,
            allowed_requester_types=tuple(t.name for t in connector.allowed_requester_types),
            consecutive_health_check_failures=connector.consecutive_health_check_failures,
        )


@dataclass(frozen=True, slots=True)
class CapabilityView:
    """05-api-contracts §"Connector Admin API": ``GET /capabilities`` row
    shape.
    """

    capability_name: str
    risk_level: str
    requires_approval: bool
    connector_count: int


@dataclass(frozen=True, slots=True)
class AuditRecordView:
    """SPEC-TG-027 "Audit Query And Admin Reporting" 12-observability
    §"Audit Observability" read shape.
    """

    audit_id: str
    action: str
    resource_type: str
    resource_id: str
    outcome: str
    actor_id: str
    correlation_id: str
    recorded_at: str
    ticket_id: str | None
    detail: str | None
    tool_request_id: str | None
    execution_id: str | None
    connector_id: str | None

    @staticmethod
    def from_domain(entry: AuditRecordEntry) -> "AuditRecordView":
        return AuditRecordView(
            audit_id=str(entry.audit_id), action=entry.action, resource_type=entry.resource_type,
            resource_id=entry.resource_id, outcome=entry.outcome, actor_id=entry.actor_id,
            correlation_id=entry.correlation_id, recorded_at=entry.recorded_at.isoformat(), ticket_id=entry.ticket_id,
            detail=entry.detail, tool_request_id=entry.tool_request_id, execution_id=entry.execution_id,
            connector_id=entry.connector_id,
        )


@dataclass(frozen=True, slots=True)
class OutboxRecordView:
    """SPEC-TG-028 "Outbox Poison Replay Admin Repair" admin-visible shape —
    never the raw domain ``OutboxRecord`` (its own ``payload`` may carry
    redacted-but-still-sensitive structured output; exposed here deliberately,
    same as the Result API's own already-redacted result).
    """

    outbox_id: str
    aggregate_type: str
    aggregate_id: str
    event_type: str
    event_version: str
    payload: dict
    status: str
    attempts: int
    occurred_at: str
    correlation_id: str

    @staticmethod
    def from_domain(record: OutboxRecord) -> "OutboxRecordView":
        return OutboxRecordView(
            outbox_id=str(record.outbox_id), aggregate_type=record.aggregate_type, aggregate_id=record.aggregate_id,
            event_type=record.event_type, event_version=record.event_version, payload=record.payload,
            status=record.status.name, attempts=record.attempts, occurred_at=record.occurred_at.isoformat(),
            correlation_id=record.correlation_id,
        )
