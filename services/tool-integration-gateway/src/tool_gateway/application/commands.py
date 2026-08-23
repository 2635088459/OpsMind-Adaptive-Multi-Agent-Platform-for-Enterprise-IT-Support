"""Input commands for the seven named 13-package-and-class-design application
modules plus ``register_connector`` (see that module's own docstring for why it
is an LLD-list extension). Plain dataclasses — no pydantic (that stays in
``tool_gateway.api.schemas``; commands are the boundary the interfaces layer
maps HTTP/event payloads onto).
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class CreateToolRequestCommand:
    """04-use-cases UC-TG-001 step 1: "Runtime calls Gateway API with
    capabilityName, input, reason, ticket/workflow/task refs, and idempotency
    key."
    """

    idempotency_key: str
    requested_by_type: str
    requested_by_id: str
    capability_name: str
    input_payload: dict
    reason: str
    correlation_id: str
    ticket_id: str | None = None
    ticket_cycle_id: str | None = None
    workflow_instance_id: str | None = None
    agent_task_id: str | None = None
    tool_name: str | None = None


@dataclass(frozen=True, slots=True)
class EvaluateToolRequestCommand:
    """04-use-cases UC-TG-002/UC-TG-003: risk decision + approval-linkage step,
    run once a ToolRequest is sitting at VALIDATING.
    """

    tool_request_id: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RecordApprovalDecisionCommand:
    """04-use-cases UC-TG-003 steps 4-5: applies an already-validated approval
    decision. Called either directly (tests, or an admin override) or by
    ``ConsumeApprovalDecisionCommand``'s own handler after dedup/linkage checks
    pass — see ``application.approve_tool_request`` module docstring.
    """

    tool_request_id: str
    approved: bool
    decided_by: str
    correlation_id: str
    denial_reason: str | None = None


@dataclass(frozen=True, slots=True)
class ConsumeApprovalDecisionCommand:
    """SPEC-TG-009 06-event-contracts: ``approval.granted.v1``/
    ``approval.denied.v1``. 09-concurrency-and-idempotency §"Approval Event
    Idempotency": event-id dedup, an already-resolved ToolRequest is a skip
    (not an error), and ``approval_request_id`` must match the stored linkage.
    """

    event_id: str
    tool_request_id: str
    approval_request_id: str
    approved: bool
    decided_by: str
    correlation_id: str
    denial_reason: str | None = None


@dataclass(frozen=True, slots=True)
class ConsumePolicyRuleChangedCommand:
    """SPEC-TG-007 06-event-contracts: ``policy.rule.changed.v1`` — "refresh
    capability risk, connector enablement, network allowlist, and approval
    requirement cache."
    """

    event_id: str
    rule_id: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeWorkflowCancelledCommand:
    """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1": "When Runtime
    workflow is cancelled, Gateway attempts to cancel associated pending/
    running Tool Requests."
    """

    event_id: str
    workflow_instance_id: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ReclaimExpiredLeasesCommand:
    """SPEC-TG-010 09-concurrency-and-idempotency §"Worker Concurrent Claim"."""

    batch_size: int = 50


@dataclass(frozen=True, slots=True)
class CancelToolRequestCommand:
    """04-use-cases UC-TG-006. 05-api-contracts: cancel "Requires idempotencyKey
    and requester" — carried through to the audit trail; the actual repeat-call
    idempotency comes from checking the CANCELLED target state itself (see
    ``application.cancel_tool_request`` module docstring), not from comparing
    this key against a stored ledger.
    """

    tool_request_id: str
    idempotency_key: str
    requested_by: str
    reason: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ExecuteToolRequestCommand:
    """Worker-invoked: claims and runs one execution attempt for a QUEUED
    ToolRequest. 13-package-and-class-design §"ToolExecutionService".
    """

    tool_request_id: str
    lease_owner: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ReconcileExecutionCommand:
    """04-use-cases UC-TG-005."""

    execution_id: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class DispatchOutboxCommand:
    """13-package-and-class-design §"OutboxPublisher"."""

    batch_size: int = 50


@dataclass(frozen=True, slots=True)
class RegisterConnectorCommand:
    """04-use-cases UC-TG-007 step 1: "Admin submits connector manifest.\""""

    name: str
    version: str
    capability_names: tuple[str, ...]
    input_schema_ref: str
    output_schema_ref: str
    risk_level: str
    requires_approval: bool
    is_mutating: bool
    secret_requirements: tuple[str, ...] = field(default_factory=tuple)
    allowed_hosts: tuple[str, ...] = field(default_factory=tuple)
    connect_timeout_seconds: int = 5
    invoke_timeout_seconds: int = 30
    max_attempts: int = 3
    backoff_seconds: int = 5
    # SPEC-TG-021: empty means unrestricted — see domain.connector.ToolConnector.
    # allowed_requester_types's own docstring. Values: "AGENT", "SYSTEM",
    # "HUMAN_OPERATOR".
    allowed_requester_types: tuple[str, ...] = field(default_factory=tuple)
    correlation_id: str = ""


@dataclass(frozen=True, slots=True)
class UpdateConnectorStatusCommand:
    """05-api-contracts §"Connector Admin API": ``PATCH /connectors/
    {connectorId}/status``.
    """

    connector_id: str
    action: str
    requested_by: str
    correlation_id: str
