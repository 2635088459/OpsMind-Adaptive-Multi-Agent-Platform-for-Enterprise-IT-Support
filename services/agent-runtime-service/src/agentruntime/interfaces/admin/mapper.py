from __future__ import annotations

from uuid import UUID

from agentruntime.application.commands import (
    CancelWorkflowCommand,
    CompleteWorkflowCommand,
    FailWorkflowCommand,
    ForceRecoverWorkflowCommand,
    RecoveryCommand,
    RetryAgentTaskCommand,
)
from agentruntime.application.records import AuditRecordEntry
from agentruntime.application.views import (
    AgentTaskView,
    DispatchReport,
    DispatchToolRequestsReport,
    LeaseRecoveryReport,
    PoisonEventView,
    RecoveryReport,
    RecoveryScanReport,
    WorkflowInstanceView,
)
from agentruntime.domain.ids import AgentTaskId, DefinitionVersion, IdempotencyKey, WorkflowInstanceId
from agentruntime.interfaces.admin.schemas import (
    AgentTaskResponse,
    AuditEventListResponse,
    AuditEventResponse,
    CancelWorkflowRequest,
    CompleteWorkflowRequest,
    DispatchReportResponse,
    DispatchToolRequestsReportResponse,
    FailWorkflowRequest,
    LeaseRecoveryScanReportResponse,
    PoisonEventListResponse,
    PoisonEventResponse,
    RecoverWorkflowRequest,
    RecoveryReportResponse,
    RecoveryScanReportResponse,
    WorkflowInstanceResponse,
)


def to_command(workflow_instance_id: UUID, request: RecoverWorkflowRequest) -> RecoveryCommand:
    expected = DefinitionVersion(request.expected_definition_version) if request.expected_definition_version is not None else None
    return RecoveryCommand(WorkflowInstanceId(workflow_instance_id), expected)


def to_complete_command(workflow_instance_id: UUID, request: CompleteWorkflowRequest) -> CompleteWorkflowCommand:
    return CompleteWorkflowCommand(WorkflowInstanceId(workflow_instance_id), IdempotencyKey(request.idempotency_key))


def to_fail_command(workflow_instance_id: UUID, request: FailWorkflowRequest) -> FailWorkflowCommand:
    return FailWorkflowCommand(WorkflowInstanceId(workflow_instance_id), IdempotencyKey(request.idempotency_key), request.failure_reason)


def to_cancel_command(workflow_instance_id: UUID, request: CancelWorkflowRequest) -> CancelWorkflowCommand:
    return CancelWorkflowCommand(WorkflowInstanceId(workflow_instance_id), IdempotencyKey(request.idempotency_key), request.reason)


def to_force_recover_command(workflow_instance_id: UUID) -> ForceRecoverWorkflowCommand:
    return ForceRecoverWorkflowCommand(WorkflowInstanceId(workflow_instance_id))


def to_retry_task_command(agent_task_id: UUID) -> RetryAgentTaskCommand:
    return RetryAgentTaskCommand(AgentTaskId(agent_task_id))


def to_agent_task_response(view: AgentTaskView) -> AgentTaskResponse:
    return AgentTaskResponse(
        agent_task_id=view.agent_task_id.value, workflow_instance_id=view.workflow_instance_id.value,
        task_key=view.task_key, state=view.state.name, task_version=view.task_version,
        updated_at=view.updated_at, agent_role=view.agent_role,
    )


def to_workflow_instance_response(view: WorkflowInstanceView) -> WorkflowInstanceResponse:
    return WorkflowInstanceResponse(
        workflow_instance_id=view.workflow_instance_id.value, state=view.state.name,
        workflow_version=view.workflow_version, pause_generation=view.pause_generation, updated_at=view.updated_at,
    )


def to_response(report: RecoveryReport) -> RecoveryReportResponse:
    return RecoveryReportResponse(
        workflow_instance_id=report.workflow_instance_id.value, state=report.state.name,
        workflow_version=report.workflow_version, definition_version=report.definition_version.value,
        recoverable_checkpoint_count=report.recoverable_checkpoint_count, open_lease_count=report.open_lease_count,
        recovered_at=report.recovered_at,
    )


def to_scan_response(report: RecoveryScanReport) -> RecoveryScanReportResponse:
    return RecoveryScanReportResponse(
        scanned=report.scanned, checkpoint_inconsistent=report.checkpoint_inconsistent, scanned_at=report.scanned_at,
    )


def to_lease_recovery_scan_response(report: LeaseRecoveryReport) -> LeaseRecoveryScanReportResponse:
    return LeaseRecoveryScanReportResponse(
        scanned=report.scanned, retried=report.retried, staled=report.staled, scanned_at=report.scanned_at,
    )


def to_dispatch_response(report: DispatchReport) -> DispatchReportResponse:
    return DispatchReportResponse(
        scanned=report.scanned, published=report.published, failed=report.failed, dead_lettered=report.dead_lettered,
        dispatched_at=report.dispatched_at,
    )


def to_dispatch_tool_requests_response(report: DispatchToolRequestsReport) -> DispatchToolRequestsReportResponse:
    return DispatchToolRequestsReportResponse(scanned=report.scanned, dispatched=report.dispatched, dispatched_at=report.dispatched_at)


def to_poison_event_response(view: PoisonEventView) -> PoisonEventResponse:
    return PoisonEventResponse(
        id=view.id, event_id=view.event_id, consumer_name=view.consumer_name, event_type=view.event_type,
        payload=view.payload, error_message=view.error_message, occurred_at=view.occurred_at, recorded_at=view.recorded_at,
        quarantined_at=view.quarantined_at,
    )


def to_poison_event_list_response(views: list[PoisonEventView]) -> PoisonEventListResponse:
    return PoisonEventListResponse(poison_events=[to_poison_event_response(view) for view in views])


def to_audit_event_response(entry: AuditRecordEntry) -> AuditEventResponse:
    return AuditEventResponse(
        id=entry.id, audit_type=entry.audit_type, action=entry.action, resource_type=entry.resource_type,
        resource_id=entry.resource_id,
        workflow_instance_id=entry.workflow_instance_id.value if entry.workflow_instance_id else None,
        ticket_id=entry.ticket_id.value if entry.ticket_id else None,
        actor_type=entry.actor_type, actor_id=entry.actor_id, outcome=entry.outcome,
        correlation_id=entry.correlation_id, causation_id=entry.causation_id, detail=entry.detail, occurred_at=entry.occurred_at,
    )


def to_audit_event_list_response(entries: list[AuditRecordEntry]) -> AuditEventListResponse:
    return AuditEventListResponse(audit_events=[to_audit_event_response(entry) for entry in entries])
