"""Input-port Protocols — what ``tool_gateway.api``/``tool_gateway.workers`` are
handed by ``tool_gateway.container`` via dependency injection. Depending on a
Protocol here (rather than a concrete Service class) keeps the interfaces layer
decoupled from which adapters the container actually wired, mirroring
memory-knowledge-service's own ``application/ports_in.py`` convention.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.application.commands import (
    CancelToolRequestCommand,
    ConsumeApprovalDecisionCommand,
    ConsumePolicyRuleChangedCommand,
    ConsumeWorkflowCancelledCommand,
    CreateToolRequestCommand,
    DispatchOutboxCommand,
    EvaluateToolRequestCommand,
    ExecuteToolRequestCommand,
    ReclaimExpiredLeasesCommand,
    ReconcileExecutionCommand,
    RecordApprovalDecisionCommand,
    RegisterConnectorCommand,
    UpdateConnectorStatusCommand,
)
from tool_gateway.application.gateway_recovery import RecoverySummary
from tool_gateway.application.views import (
    AuditRecordView,
    CapabilityView,
    ConnectorView,
    OutboxRecordView,
    RawOutputView,
    ToolRequestView,
    ToolResultView,
)


class CreateToolRequestUseCase(Protocol):
    def create_tool_request(self, command: CreateToolRequestCommand) -> ToolRequestView: ...


class EvaluateToolRequestUseCase(Protocol):
    def evaluate_tool_request(self, command: EvaluateToolRequestCommand) -> ToolRequestView: ...


class ApproveToolRequestUseCase(Protocol):
    def record_approval_decision(self, command: RecordApprovalDecisionCommand) -> ToolRequestView: ...

    def consume_approval_decision(self, command: ConsumeApprovalDecisionCommand) -> ToolRequestView: ...


class PolicyRuleChangeConsumerUseCase(Protocol):
    def consume_policy_rule_changed(self, command: ConsumePolicyRuleChangedCommand) -> bool:
        """Returns True if newly applied, False if a deduplicated replay."""
        ...


class CancelToolRequestUseCase(Protocol):
    def cancel_tool_request(self, command: CancelToolRequestCommand) -> ToolRequestView: ...


class WorkflowCancelledConsumerUseCase(Protocol):
    def consume_workflow_cancelled(self, command: ConsumeWorkflowCancelledCommand) -> int:
        """Returns the number of Tool Requests actually cancelled."""
        ...


class ExecuteToolRequestUseCase(Protocol):
    def execute_tool_request(self, command: ExecuteToolRequestCommand) -> ToolRequestView: ...


class ReconcileExecutionUseCase(Protocol):
    def reconcile_execution(self, command: ReconcileExecutionCommand) -> ToolRequestView: ...


class ReclaimExpiredLeasesUseCase(Protocol):
    def reclaim_expired_leases(self, command: ReclaimExpiredLeasesCommand) -> int:
        """Returns the number of lease-expired attempts reclaimed."""
        ...


class PublishOutboxUseCase(Protocol):
    def dispatch(self, command: DispatchOutboxCommand) -> int:
        """Returns the number of outbox records successfully published."""
        ...


class RegisterConnectorUseCase(Protocol):
    def register_connector(self, command: RegisterConnectorCommand) -> ConnectorView: ...

    def list_connectors(self) -> list[ConnectorView]: ...

    def find_connector(self, connector_id: str) -> ConnectorView:
        """SPEC-TG-029: ``GET /connectors/{connectorId}`` single lookup."""
        ...

    def update_connector_status(self, command: UpdateConnectorStatusCommand) -> ConnectorView: ...

    def apply_health_check_result(self, connector_id: object, healthy: bool, correlation_id: str) -> ConnectorView:
        """SPEC-TG-019: the automatic ACTIVE<->DEGRADED half of the Connector
        Health State Machine, driven by ``workers.connector_health_worker``.
        """
        ...

    def list_capabilities(self) -> list[CapabilityView]: ...


class ToolRequestQueryUseCase(Protocol):
    def find_tool_request(self, tool_request_id: str) -> ToolRequestView: ...


class ToolResultQueryUseCase(Protocol):
    def find_result(self, result_envelope_id: str) -> ToolResultView: ...

    def find_raw_output(
        self, result_envelope_id: str, requested_by_type: str, requested_by_id: str, reason: str, correlation_id: str,
    ) -> RawOutputView:
        """SPEC-TG-020: privileged, audited raw-output access — see
        ``RawOutputForbiddenException``'s own docstring for the gate.
        """
        ...


class AuditQueryUseCase(Protocol):
    """SPEC-TG-027 "Audit Query And Admin Reporting"."""

    def find_recent(self, limit: int) -> list[AuditRecordView]: ...

    def find_by_ticket_id(self, ticket_id: str, limit: int) -> list[AuditRecordView]: ...

    def find_by_actor_id(self, actor_id: str, limit: int) -> list[AuditRecordView]: ...

    def find_by_connector_id(self, connector_id: str, limit: int) -> list[AuditRecordView]: ...


class AdminOutboxUseCase(Protocol):
    """SPEC-TG-028 "Outbox Poison Replay Admin Repair"."""

    def list_dead_letter(self, limit: int) -> list[OutboxRecordView]: ...

    def replay(self, outbox_id: str, requested_by: str, correlation_id: str) -> OutboxRecordView: ...


class GatewayRecoveryUseCase(Protocol):
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
    §"Gateway Crash Recovery" — see ``application.gateway_recovery`` module
    docstring for why this is admin-triggered rather than run automatically at
    process boot.
    """

    def run_recovery(self, batch_size: int) -> RecoverySummary: ...
