"""Domain enums. Pure stdlib — no framework dependency. Member sets mirror
docs/low-level-design/domains/05-tool-integration-gateway/01-domain-model and
03-state-machine exactly, even though 03-state-machine is not itself in this
spec's LLD mapping (13-package-and-class-design, 02-business-invariants) — the
package boundary these enums live in has to be shaped so later phase specs
(phase-01 request-intake-and-registry .. phase-04 retry-reconciliation-
cancellation) never need to change it again, mirroring memory-knowledge-
service's own SPEC-MK-001 precedent for the same situation.
"""

from __future__ import annotations

from enum import Enum, auto


class RequestedByType(Enum):
    """01-domain-model §"ToolRequest": "requestedByType: AGENT, SYSTEM, or
    HUMAN_OPERATOR."
    """

    AGENT = auto()
    SYSTEM = auto()
    HUMAN_OPERATOR = auto()


class ToolRequestStatus(Enum):
    """03-state-machine §"Tool Request State Machine"."""

    RECEIVED = auto()
    VALIDATING = auto()
    POLICY_CHECKING = auto()
    WAITING_APPROVAL = auto()
    APPROVED = auto()
    QUEUED = auto()
    EXECUTING = auto()
    COMPLETED = auto()
    REJECTED = auto()
    POLICY_DENIED = auto()
    APPROVAL_DENIED = auto()
    CANCEL_REQUESTED = auto()
    CANCELLED = auto()
    FAILED = auto()
    TERMINAL_FAILED = auto()

    def is_terminal(self) -> bool:
        """03-state-machine §"State Separation": once COMPLETED/REJECTED/
        POLICY_DENIED/APPROVAL_DENIED/CANCELLED/TERMINAL_FAILED, a ToolRequest
        publishes no further ``tool.completed.v1``/``tool.cancelled.v1`` and
        accepts no further transitions.
        """
        return self in _TERMINAL_REQUEST_STATUSES


_TERMINAL_REQUEST_STATUSES = frozenset({
    ToolRequestStatus.COMPLETED, ToolRequestStatus.REJECTED, ToolRequestStatus.POLICY_DENIED,
    ToolRequestStatus.APPROVAL_DENIED, ToolRequestStatus.CANCELLED, ToolRequestStatus.TERMINAL_FAILED,
})


class ToolExecutionStatus(Enum):
    """03-state-machine §"Execution Attempt State Machine"."""

    CREATED = auto()
    CLAIMED = auto()
    PREPARING = auto()
    INVOKING = auto()
    NORMALIZING_RESULT = auto()
    COMPLETED = auto()
    LEASE_EXPIRED = auto()
    FAILED = auto()
    TIMED_OUT = auto()
    PARTIAL_SIDE_EFFECT = auto()
    RETRY_SCHEDULED = auto()
    RECONCILING = auto()
    TERMINAL_FAILED = auto()

    def is_terminal(self) -> bool:
        return self in _TERMINAL_EXECUTION_STATUSES


_TERMINAL_EXECUTION_STATUSES = frozenset({
    ToolExecutionStatus.COMPLETED, ToolExecutionStatus.LEASE_EXPIRED, ToolExecutionStatus.TERMINAL_FAILED,
})


class ApprovalLinkageStatus(Enum):
    """03-state-machine §"Approval Linkage State Machine": "Gateway stores only
    approval linkage and decision snapshots. Approval rules, approvers, approval
    SLA, and approval history are owned by 06-policy-approval-governance."
    """

    NOT_REQUIRED = auto()
    REQUIRED = auto()
    APPROVAL_REQUESTED = auto()
    APPROVED = auto()
    DENIED = auto()
    EXPIRED = auto()
    CANCELLED = auto()


class ConnectorHealthStatus(Enum):
    """03-state-machine §"Connector Health State Machine": "Scheduling may select
    only ACTIVE connectors. A DEGRADED connector is allowed only for read-only or
    low-risk fallback unless policy explicitly permits otherwise."
    """

    ACTIVE = auto()
    DEGRADED = auto()
    DISABLED = auto()
    DEPRECATED = auto()

    def is_schedulable(self) -> bool:
        return self is ConnectorHealthStatus.ACTIVE


class RiskLevel(Enum):
    """01-domain-model §"ToolConnector": "riskLevel" field; 02-business-invariants
    INV-TG-005: a HIGH/CRITICAL decision must wait for approval.
    """

    LOW = auto()
    MEDIUM = auto()
    HIGH = auto()
    CRITICAL = auto()


class SideEffectKind(Enum):
    """02-business-invariants (domain-rules) §"Required": "Mutation connectors
    must have an operation key." Not itself a named 01-domain-model field, but
    required to make that rule mechanically enforceable — added the same way
    memory-knowledge-service's own SPEC-MK-001 extended its LLD-listed port set
    (CommandIdempotencyRepository, DocumentParserPort) when the literal field
    list left a rule otherwise unenforceable in code.
    """

    READ_ONLY = auto()
    MUTATING = auto()


class RedactionStatus(Enum):
    """01-domain-model §"ToolResultEnvelope": "redactionStatus" field;
    02-business-invariants INV-TG-007: raw output is not published by default.
    """

    NOT_REQUIRED = auto()
    REDACTED = auto()
    REVIEW_REQUIRED = auto()


class ResultStatus(Enum):
    """01-domain-model §"ToolResultEnvelope": "status" field; 02-business-
    invariants INV-TG-010: "Connector timeout, policy denial, approval denial,
    non-retryable failure, and partial side effect must remain distinguishable.
    They must not be collapsed into a generic failure."
    """

    SUCCESS = auto()
    FAILED = auto()
    TIMED_OUT = auto()
    PARTIAL_SIDE_EFFECT = auto()
    POLICY_DENIED = auto()
    APPROVAL_DENIED = auto()
    CANCELLED = auto()
    UNCERTAIN = auto()


class OutboxStatus(Enum):
    """08-transaction-and-outbox (deferred to SPEC-TG-003) §"Outbox Publisher":
    mirrors memory-knowledge-service's own OutboxStatus exactly — introduced here
    already since "所有发布事件必须通过 Gateway outbox" (00-implementation-roadmap
    §"Closure Principles") is a phase-00 mandatory constraint this spec's
    publish_outbox use case already has to satisfy.
    """

    PENDING = auto()
    PUBLISHED = auto()
    DEAD_LETTER = auto()
