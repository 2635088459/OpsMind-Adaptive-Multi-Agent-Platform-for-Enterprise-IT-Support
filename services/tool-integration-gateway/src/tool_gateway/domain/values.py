"""01-domain-model §"Value Objects": RiskDecisionRef, ApprovalRequestRef,
ConnectorInvocationSpec, RedactionMetadata, AuditActor — plus the connector
manifest sub-value-objects (NetworkPolicy, TimeoutPolicy, RetryPolicy) that
01-domain-model's own ToolConnector field list names but does not shape, mirrored
after memory-knowledge-service's own domain/values.py convention (frozen,
self-validating, no framework dependency).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from tool_gateway.domain.enums import ApprovalLinkageStatus, RequestedByType, ResultStatus, RiskLevel
from tool_gateway.domain.ids import ApprovalRequestId


@dataclass(frozen=True, slots=True)
class RiskDecisionRef:
    """01-domain-model §"Value Objects": "reference to the Policy/Approval risk
    decision." 02-business-invariants INV-TG-005/INV-TG-009: execution combines
    actor, tenant, ticket scope, risk, policy, and credential binding — this is
    the risk half of that combination, as decided by (today, a placeholder
    stand-in for) 06-policy-approval-governance.

    ``denied``/``denial_reason`` are SPEC-TG-007 additions beyond 01-domain-
    model's own field list — 10-failure-handling §"Policy / Approval Failure":
    "Policy denied ... Gateway publishes final tool.completed.v1 with status
    POLICY_DENIED" names an outcome the original field list (risk_level +
    requires_approval only) cannot represent: a hard policy rule can refuse a
    capability outright, independent of risk-based approval routing.
    """

    decision_id: str
    risk_level: RiskLevel
    requires_approval: bool
    decided_at: datetime
    decided_by: str
    denied: bool = False
    denial_reason: str | None = None


@dataclass(frozen=True, slots=True)
class ApprovalRequestRef:
    """01-domain-model §"Value Objects": "reference to an approval request."
    03-state-machine §"Approval Linkage State Machine": "Gateway stores only
    approval linkage and decision snapshots" — approval rules/approvers/SLA/
    history stay owned by 06-policy-approval-governance.
    """

    approval_request_id: ApprovalRequestId
    status: ApprovalLinkageStatus
    requested_at: datetime
    decided_at: datetime | None = None
    decided_by: str | None = None


@dataclass(frozen=True, slots=True)
class ConnectorInvocationSpec:
    """01-domain-model §"Value Objects": "standard input passed to connectors."
    Carries no credential *value* — only a binding id the connector adapter
    resolves transiently at invocation time (INV-TG-004).
    """

    connector_id: str
    connector_version: str
    operation_key: str | None
    input_payload: dict
    timeout_seconds: int
    credential_binding_id: str | None = None


@dataclass(frozen=True, slots=True)
class RedactionMetadata:
    """01-domain-model §"Value Objects": "output redaction and classification
    result."
    """

    status: str
    redacted_fields: tuple[str, ...]
    applied_at: datetime


@dataclass(frozen=True, slots=True)
class AuditActor:
    """01-domain-model §"Value Objects": "requester, approver, worker, and
    connector identity." INV-TG-006: every mandatory audit record names who.
    """

    actor_type: RequestedByType
    actor_id: str


@dataclass(frozen=True, slots=True)
class NetworkPolicy:
    """01-domain-model §"ToolConnector": "networkPolicy" field."""

    allowed_hosts: tuple[str, ...]
    deny_by_default: bool = True


@dataclass(frozen=True, slots=True)
class TimeoutPolicy:
    """01-domain-model §"ToolConnector": "timeoutPolicy" field."""

    connect_timeout_seconds: int
    invoke_timeout_seconds: int


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """01-domain-model §"ToolConnector": "retryPolicy" field. Consumed by
    phase-04 (SPEC-TG-016 retry policy); defined here so ToolConnector's manifest
    shape does not need to change when that spec lands.
    """

    max_attempts: int
    backoff_seconds: int


@dataclass(frozen=True, slots=True)
class ExecutionOutcome:
    """The normalized, redaction-agnostic fact a ConnectorPort.invoke()/
    reconcile() call reports back to the application layer — distinct from
    ToolResultEnvelope, which is the *persisted*, already-redacted record built
    from this outcome (domain-rules §"Forbidden": "Writing connector raw output
    directly into Memory Knowledge" — raw_output here never crosses into a
    ToolResultEnvelope field; only summary/structured_output/raw_output_ref do).
    """

    status: ResultStatus
    summary: str
    structured_output: dict
    raw_output: str | None
    error_code: str | None
    retryable: bool
