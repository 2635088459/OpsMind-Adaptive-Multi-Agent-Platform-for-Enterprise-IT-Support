from __future__ import annotations

from uuid import UUID

from agentruntime.application.commands import CancelWorkflowCommand, CompleteWorkflowCommand, FailWorkflowCommand, RecoveryCommand
from agentruntime.application.views import DispatchReport, RecoveryReport, WorkflowInstanceView
from agentruntime.domain.ids import DefinitionVersion, IdempotencyKey, WorkflowInstanceId
from agentruntime.interfaces.admin.schemas import (
    CancelWorkflowRequest,
    CompleteWorkflowRequest,
    DispatchReportResponse,
    FailWorkflowRequest,
    RecoverWorkflowRequest,
    RecoveryReportResponse,
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


def to_dispatch_response(report: DispatchReport) -> DispatchReportResponse:
    return DispatchReportResponse(
        scanned=report.scanned, published=report.published, failed=report.failed, dead_lettered=report.dead_lettered,
        dispatched_at=report.dispatched_at,
    )
