"""13-package-and-class-design §"api/schemas.py": pydantic request/response
DTOs. The only module in ``tool_gateway.api`` allowed to import pydantic
directly — routers import only these plus the ports_in Protocols.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class SubmitToolRequestRequest(BaseModel):
    idempotency_key: str
    requested_by_type: str = Field(description="AGENT, SYSTEM, or HUMAN_OPERATOR")
    requested_by_id: str
    capability_name: str
    input_payload: dict = Field(default_factory=dict)
    reason: str
    correlation_id: str
    ticket_id: str | None = None
    ticket_cycle_id: str | None = None
    workflow_instance_id: str | None = None
    agent_task_id: str | None = None
    tool_name: str | None = None
    context_refs: tuple[str, ...] = Field(
        default=(), description=(
            "05-api-contracts §\"Runtime API\" request example. Accepted for wire "
            "compatibility but not yet threaded into the domain model or persisted "
            "— 01-domain-model's own ToolRequest field list has no slot for it; "
            "phase-06 SPEC-TG-024 \"memory-evidence-contract\" is expected to define "
            "what a context ref actually does."
        ),
    )


class ApprovalDecisionRequest(BaseModel):
    approved: bool
    decided_by: str
    correlation_id: str
    denial_reason: str | None = None


class CancelToolRequestRequest(BaseModel):
    idempotency_key: str = Field(description="05-api-contracts: cancel \"Requires idempotencyKey and requester.\"")
    requested_by: str
    reason: str
    correlation_id: str


class ToolRequestResponse(BaseModel):
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


class ToolResultResponse(BaseModel):
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


class RawOutputResponse(BaseModel):
    result_envelope_id: str
    raw_output: str | None


class RegisterConnectorRequest(BaseModel):
    name: str
    version: str
    capability_names: tuple[str, ...]
    input_schema_ref: str
    output_schema_ref: str
    risk_level: str = Field(description="LOW, MEDIUM, HIGH, or CRITICAL")
    requires_approval: bool
    is_mutating: bool
    secret_requirements: tuple[str, ...] = ()
    allowed_hosts: tuple[str, ...] = ()
    connect_timeout_seconds: int = 5
    invoke_timeout_seconds: int = 30
    max_attempts: int = 3
    backoff_seconds: int = 5
    allowed_requester_types: tuple[str, ...] = Field(
        default=(), description="Empty means unrestricted. Values: AGENT, SYSTEM, HUMAN_OPERATOR.",
    )
    correlation_id: str = ""


class ConnectorResponse(BaseModel):
    connector_id: str
    name: str
    version: str
    capabilities: tuple[str, ...]
    risk_level: str
    requires_approval: bool
    health_status: str
    side_effect_kind: str
    secret_requirements: tuple[str, ...]
    allowed_hosts: tuple[str, ...]
    deny_by_default: bool
    connect_timeout_seconds: int
    invoke_timeout_seconds: int
    max_attempts: int
    backoff_seconds: int
    allowed_requester_types: tuple[str, ...]
    consecutive_health_check_failures: int = 0


class UpdateConnectorStatusRequest(BaseModel):
    action: Literal["ENABLE", "DISABLE", "DEPRECATE"]
    requested_by: str
    correlation_id: str


class CapabilityResponse(BaseModel):
    capability_name: str
    risk_level: str
    requires_approval: bool
    connector_count: int


class ApprovalGrantedEventRequest(BaseModel):
    """06-event-contracts §"Consumed Events" §"approval.granted.v1"."""

    event_id: str
    approval_request_id: str
    tool_request_id: str
    ticket_id: str | None = None
    workflow_instance_id: str | None = None
    approved_by: str
    decision_at: str | None = None
    constraints: dict = Field(default_factory=dict)
    correlation_id: str


class ApprovalDeniedEventRequest(BaseModel):
    """06-event-contracts §"Consumed Events" §"approval.denied.v1"."""

    event_id: str
    approval_request_id: str
    tool_request_id: str
    ticket_id: str | None = None
    workflow_instance_id: str | None = None
    denied_by: str
    decision_at: str | None = None
    denial_reason: str | None = None
    correlation_id: str


class PolicyRuleChangedEventRequest(BaseModel):
    """06-event-contracts §"Consumed Events" §"policy.rule.changed.v1"."""

    event_id: str
    rule_id: str
    correlation_id: str


class EventIngestResponse(BaseModel):
    event_id: str
    applied: bool


class WorkflowCancelledEventRequest(BaseModel):
    """SPEC-TG-022 06-event-contracts §"Consumed Events" §"workflow.cancelled.v1"."""

    event_id: str
    workflow_instance_id: str
    correlation_id: str


class WorkflowCancelledIngestResponse(BaseModel):
    event_id: str
    cancelled_count: int


class AuditRecordResponse(BaseModel):
    """SPEC-TG-027 "Audit Query And Admin Reporting"."""

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


class OutboxRecordResponse(BaseModel):
    """SPEC-TG-028 "Outbox Poison Replay Admin Repair"."""

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


class ReplayOutboxRequest(BaseModel):
    requested_by: str
    correlation_id: str = ""


class RecoverySummaryResponse(BaseModel):
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling"."""

    leases_reclaimed: int
    outbox_events_published: int
