"""13-package-and-class-design §"Main Classes": ``state_machine.py`` is the single
source of truth for every transition table this domain enforces — ToolRequest,
ToolExecution, ApprovalLinkage, and ConnectorHealth all validate against a table
defined here rather than each hard-coding its own adjacency rules, so
03-state-machine only has to be transcribed once.

Every table below is transcribed directly from 03-state-machine's own ASCII
diagrams. Two edges are added beyond the literal diagram text, each cited to the
use case that requires it (03-state-machine's diagrams are illustrative of the
mandatory path, not an exhaustive edge list — the same gap memory-knowledge-
service's own 03-state-machine diagrams have around their own auto-approval-style
shortcuts):

- ``POLICY_CHECKING -> APPROVED``: 04-use-cases UC-TG-002 step 2 ("If policy marks
  it as low-risk/no-approval, ToolRequest enters QUEUED") — the diagram's literal
  path is POLICY_CHECKING -> WAITING_APPROVAL -> APPROVED -> QUEUED, but a
  no-approval-required decision has nothing to wait on, so it reaches APPROVED
  directly and then still passes through the diagram's own explicit
  APPROVED -> QUEUED edge.
- ``CANCEL_REQUESTED -> COMPLETED``: 04-use-cases UC-TG-006 step 5 ("Gateway
  finally publishes tool.cancelled.v1 or tool.completed.v1 with cancellation
  metadata") — a cancellation requested mid-execution can still race a connector
  call that finishes anyway; the fact must stay COMPLETED, not be forced into
  CANCELLED and hide that the side effect actually completed.
"""

from __future__ import annotations

from tool_gateway.domain.enums import ApprovalLinkageStatus, ConnectorHealthStatus, ToolExecutionStatus, ToolRequestStatus

TOOL_REQUEST_TRANSITIONS: dict[ToolRequestStatus, frozenset[ToolRequestStatus]] = {
    ToolRequestStatus.RECEIVED: frozenset({ToolRequestStatus.VALIDATING, ToolRequestStatus.REJECTED}),
    ToolRequestStatus.VALIDATING: frozenset({ToolRequestStatus.POLICY_CHECKING, ToolRequestStatus.REJECTED}),
    ToolRequestStatus.POLICY_CHECKING: frozenset({
        ToolRequestStatus.WAITING_APPROVAL, ToolRequestStatus.APPROVED, ToolRequestStatus.POLICY_DENIED,
    }),
    ToolRequestStatus.WAITING_APPROVAL: frozenset({ToolRequestStatus.APPROVED, ToolRequestStatus.APPROVAL_DENIED}),
    ToolRequestStatus.APPROVED: frozenset({ToolRequestStatus.QUEUED}),
    ToolRequestStatus.QUEUED: frozenset({ToolRequestStatus.EXECUTING, ToolRequestStatus.CANCELLED}),
    ToolRequestStatus.EXECUTING: frozenset({
        ToolRequestStatus.COMPLETED, ToolRequestStatus.FAILED, ToolRequestStatus.CANCEL_REQUESTED,
    }),
    ToolRequestStatus.FAILED: frozenset({ToolRequestStatus.QUEUED, ToolRequestStatus.TERMINAL_FAILED}),
    ToolRequestStatus.CANCEL_REQUESTED: frozenset({ToolRequestStatus.CANCELLED, ToolRequestStatus.COMPLETED}),
    ToolRequestStatus.COMPLETED: frozenset(),
    ToolRequestStatus.REJECTED: frozenset(),
    ToolRequestStatus.POLICY_DENIED: frozenset(),
    ToolRequestStatus.APPROVAL_DENIED: frozenset(),
    ToolRequestStatus.CANCELLED: frozenset(),
    ToolRequestStatus.TERMINAL_FAILED: frozenset(),
}

TOOL_EXECUTION_TRANSITIONS: dict[ToolExecutionStatus, frozenset[ToolExecutionStatus]] = {
    ToolExecutionStatus.CREATED: frozenset({ToolExecutionStatus.CLAIMED}),
    ToolExecutionStatus.CLAIMED: frozenset({ToolExecutionStatus.PREPARING, ToolExecutionStatus.LEASE_EXPIRED}),
    ToolExecutionStatus.PREPARING: frozenset({ToolExecutionStatus.INVOKING, ToolExecutionStatus.FAILED}),
    ToolExecutionStatus.INVOKING: frozenset({
        ToolExecutionStatus.NORMALIZING_RESULT, ToolExecutionStatus.TIMED_OUT,
        ToolExecutionStatus.FAILED, ToolExecutionStatus.PARTIAL_SIDE_EFFECT,
    }),
    ToolExecutionStatus.NORMALIZING_RESULT: frozenset({ToolExecutionStatus.COMPLETED, ToolExecutionStatus.FAILED}),
    ToolExecutionStatus.FAILED: frozenset({ToolExecutionStatus.RETRY_SCHEDULED}),
    ToolExecutionStatus.TIMED_OUT: frozenset({ToolExecutionStatus.RECONCILING}),
    ToolExecutionStatus.PARTIAL_SIDE_EFFECT: frozenset({ToolExecutionStatus.RECONCILING}),
    ToolExecutionStatus.RECONCILING: frozenset({ToolExecutionStatus.COMPLETED, ToolExecutionStatus.TERMINAL_FAILED}),
    ToolExecutionStatus.COMPLETED: frozenset(),
    ToolExecutionStatus.LEASE_EXPIRED: frozenset(),
    ToolExecutionStatus.RETRY_SCHEDULED: frozenset(),
    ToolExecutionStatus.TERMINAL_FAILED: frozenset(),
}

APPROVAL_LINKAGE_TRANSITIONS: dict[ApprovalLinkageStatus, frozenset[ApprovalLinkageStatus]] = {
    ApprovalLinkageStatus.NOT_REQUIRED: frozenset(),
    ApprovalLinkageStatus.REQUIRED: frozenset({ApprovalLinkageStatus.APPROVAL_REQUESTED}),
    ApprovalLinkageStatus.APPROVAL_REQUESTED: frozenset({
        ApprovalLinkageStatus.APPROVED, ApprovalLinkageStatus.DENIED,
        ApprovalLinkageStatus.EXPIRED, ApprovalLinkageStatus.CANCELLED,
    }),
    ApprovalLinkageStatus.APPROVED: frozenset(),
    ApprovalLinkageStatus.DENIED: frozenset(),
    ApprovalLinkageStatus.EXPIRED: frozenset(),
    ApprovalLinkageStatus.CANCELLED: frozenset(),
}

CONNECTOR_HEALTH_TRANSITIONS: dict[ConnectorHealthStatus, frozenset[ConnectorHealthStatus]] = {
    ConnectorHealthStatus.ACTIVE: frozenset({
        ConnectorHealthStatus.DEGRADED, ConnectorHealthStatus.DISABLED, ConnectorHealthStatus.DEPRECATED,
    }),
    ConnectorHealthStatus.DEGRADED: frozenset({ConnectorHealthStatus.ACTIVE, ConnectorHealthStatus.DISABLED}),
    ConnectorHealthStatus.DISABLED: frozenset({ConnectorHealthStatus.ACTIVE}),
    ConnectorHealthStatus.DEPRECATED: frozenset({ConnectorHealthStatus.DISABLED}),
}


def is_allowed(current: object, target: object, table: dict) -> bool:
    """Generic guard shared by every aggregate's transition methods."""

    return target in table.get(current, frozenset())
