"""Domain exceptions — raised by domain aggregates when a rule is violated using
only information the aggregate already carries (no I/O, no repository lookups).
Distinct from application-layer exceptions (tool_gateway.application.exceptions),
raised after I/O a pure domain function must not perform.
"""

from __future__ import annotations

from tool_gateway.domain.enums import ApprovalLinkageStatus, ConnectorHealthStatus, ToolExecutionStatus, ToolRequestStatus


class InvalidToolRequestTransitionException(RuntimeError):
    """03-state-machine §"Tool Request State Machine": attempted a transition the
    current status does not allow.
    """

    def __init__(self, current_status: ToolRequestStatus, target_status: ToolRequestStatus) -> None:
        super().__init__(f"tool request cannot transition from {current_status} to {target_status}")
        self.current_status = current_status
        self.target_status = target_status


class InvalidToolExecutionTransitionException(RuntimeError):
    """03-state-machine §"Execution Attempt State Machine"."""

    def __init__(self, current_status: ToolExecutionStatus, target_status: ToolExecutionStatus) -> None:
        super().__init__(f"tool execution cannot transition from {current_status} to {target_status}")
        self.current_status = current_status
        self.target_status = target_status


class InvalidApprovalLinkageTransitionException(RuntimeError):
    """03-state-machine §"Approval Linkage State Machine"."""

    def __init__(self, current_status: ApprovalLinkageStatus, target_status: ApprovalLinkageStatus) -> None:
        super().__init__(f"approval linkage cannot transition from {current_status} to {target_status}")
        self.current_status = current_status
        self.target_status = target_status


class InvalidConnectorHealthTransitionException(RuntimeError):
    """03-state-machine §"Connector Health State Machine"."""

    def __init__(self, current_status: ConnectorHealthStatus, target_status: ConnectorHealthStatus) -> None:
        super().__init__(f"connector cannot transition from {current_status} to {target_status}")
        self.current_status = current_status
        self.target_status = target_status


class ToolRequestMissingReasonException(RuntimeError):
    """01-domain-model §"ToolRequest": "reason: auditable reason for execution" —
    INV-TG-006 makes every request accepted/rejected an audited fact, which is
    unenforceable if a request can carry no reason for a human/auditor to read.
    """

    def __init__(self) -> None:
        super().__init__("tool request must carry a non-blank reason")


class MutationConnectorMissingOperationKeyException(RuntimeError):
    """domain-rules §"Required": "Mutation connectors must have an operation key."
    INV-TG-003: "Every connector that may mutate an external system must have an
    operationKey."
    """

    def __init__(self) -> None:
        super().__init__("a mutating connector execution attempt must carry a non-blank operationKey")


class ActiveExecutionAlreadyExistsException(RuntimeError):
    """01-domain-model §"Aggregate Rules": "A ToolRequest may have multiple
    ToolExecution attempts, but only one active attempt at a time."
    """

    def __init__(self, tool_request_id: object) -> None:
        super().__init__(f"tool request {tool_request_id} already has an active execution attempt")
        self.tool_request_id = tool_request_id


class ResultEnvelopeMissingSummaryException(RuntimeError):
    """01-domain-model §"ToolResultEnvelope": every envelope must carry a
    ``summary`` — the human/auditor-readable fact even when structured_output or
    raw_output_ref is unavailable (e.g. a connector timeout).
    """

    def __init__(self) -> None:
        super().__init__("tool result envelope must carry a non-blank summary")
