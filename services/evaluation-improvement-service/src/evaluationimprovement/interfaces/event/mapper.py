"""13-package-and-class-design §"Interfaces": DTO mapping only, no business rules."""

from __future__ import annotations

from evaluationimprovement.application.commands import (
    ConsumeApprovalDeniedCommand,
    ConsumeApprovalGrantedCommand,
    ConsumeMemoryRetrievalCompletedCommand,
    ConsumeTicketReopenedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeToolCompletedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
)
from evaluationimprovement.interfaces.event.schemas import (
    ApprovalDeniedEventRequest,
    ApprovalGrantedEventRequest,
    MemoryRetrievalCompletedEventRequest,
    TicketReopenedEventRequest,
    TicketResolvedEventRequest,
    ToolCompletedEventRequest,
    WorkflowCompletedEventRequest,
    WorkflowFailedEventRequest,
)


def to_consume_ticket_resolved_command(request: TicketResolvedEventRequest) -> ConsumeTicketResolvedCommand:
    return ConsumeTicketResolvedCommand(
        event_id=request.event_id, ticket_id=request.ticket_id, resolution_code=request.resolution_code,
        resolution_summary=request.resolution_summary, resolved_at=request.resolved_at, correlation_id=request.correlation_id,
    )


def to_consume_ticket_reopened_command(request: TicketReopenedEventRequest) -> ConsumeTicketReopenedCommand:
    return ConsumeTicketReopenedCommand(
        event_id=request.event_id, ticket_id=request.ticket_id, reopen_reason_code=request.reopen_reason_code,
        reopen_count=request.reopen_count, reopened_at=request.reopened_at, correlation_id=request.correlation_id,
    )


def to_consume_workflow_completed_command(request: WorkflowCompletedEventRequest) -> ConsumeWorkflowCompletedCommand:
    return ConsumeWorkflowCompletedCommand(
        event_id=request.event_id, workflow_instance_id=request.workflow_instance_id, ticket_id=request.ticket_id,
        to_state=request.to_state, workflow_version=request.workflow_version, occurred_at=request.occurred_at,
        correlation_id=request.correlation_id,
    )


def to_consume_workflow_failed_command(request: WorkflowFailedEventRequest) -> ConsumeWorkflowFailedCommand:
    return ConsumeWorkflowFailedCommand(
        event_id=request.event_id, workflow_instance_id=request.workflow_instance_id, ticket_id=request.ticket_id,
        to_state=request.to_state, workflow_version=request.workflow_version, failure_reason=request.failure_reason,
        occurred_at=request.occurred_at, correlation_id=request.correlation_id,
    )


def to_consume_tool_completed_command(request: ToolCompletedEventRequest) -> ConsumeToolCompletedCommand:
    return ConsumeToolCompletedCommand(
        event_id=request.event_id, tool_request_id=request.tool_request_id, capability_name=request.capability_name,
        status=request.status, redaction_status=request.redaction_status, error_code=request.error_code,
        occurred_at=request.occurred_at, correlation_id=request.correlation_id,
    )


def to_consume_memory_retrieval_completed_command(request: MemoryRetrievalCompletedEventRequest) -> ConsumeMemoryRetrievalCompletedCommand:
    return ConsumeMemoryRetrievalCompletedCommand(
        event_id=request.event_id, query_id=request.query_id, memory_type=request.memory_type,
        result_count=request.result_count, acl_scope_denied=request.acl_scope_denied, occurred_at=request.occurred_at,
        correlation_id=request.correlation_id,
    )


def to_consume_approval_granted_command(request: ApprovalGrantedEventRequest) -> ConsumeApprovalGrantedCommand:
    return ConsumeApprovalGrantedCommand(
        event_id=request.event_id, approval_request_id=request.approval_request_id, source_domain=request.source_domain,
        source_request_id=request.source_request_id, decided_by=request.decided_by, correlation_id=request.correlation_id,
    )


def to_consume_approval_denied_command(request: ApprovalDeniedEventRequest) -> ConsumeApprovalDeniedCommand:
    return ConsumeApprovalDeniedCommand(
        event_id=request.event_id, approval_request_id=request.approval_request_id, source_domain=request.source_domain,
        source_request_id=request.source_request_id, decided_by=request.decided_by, reason=request.reason,
        correlation_id=request.correlation_id,
    )
