from __future__ import annotations

from uuid import UUID

from agentruntime.application.commands import ClaimAgentTaskCommand, ClaimReadyAgentTasksCommand, CompleteAgentTaskCommand, RequestToolCommand
from agentruntime.application.views import AgentTaskView, ToolRequestView
from agentruntime.domain.ids import AgentTaskId, IdempotencyKey, LeaseToken, WorkflowInstanceId
from agentruntime.interfaces.worker.schemas import (
    AgentTaskResponse,
    ClaimAgentTaskRequest,
    ClaimReadyAgentTasksRequest,
    ClaimReadyAgentTasksResponse,
    CompleteAgentTaskRequest,
    RequestToolRequest,
    ToolRequestResponse,
)


def to_claim_command(request: ClaimAgentTaskRequest) -> ClaimAgentTaskCommand:
    return ClaimAgentTaskCommand(WorkflowInstanceId(request.workflow_instance_id), request.task_key, request.worker_id, request.lease_seconds)


def to_claim_ready_command(request: ClaimReadyAgentTasksRequest) -> ClaimReadyAgentTasksCommand:
    return ClaimReadyAgentTasksCommand(request.agent_role, request.worker_id, request.max_tasks, request.lease_seconds)


def to_complete_command(agent_task_id: UUID, request: CompleteAgentTaskRequest) -> CompleteAgentTaskCommand:
    return CompleteAgentTaskCommand(
        AgentTaskId(agent_task_id), LeaseToken(request.claim_token), IdempotencyKey(request.idempotency_key),
        workflow_version=request.workflow_version, result_payload=request.result_payload, failure_reason=request.failure_reason,
    )


def to_request_tool_command(request: RequestToolRequest) -> RequestToolCommand:
    return RequestToolCommand(
        WorkflowInstanceId(request.workflow_instance_id), AgentTaskId(request.agent_task_id),
        request.checkpoint_payload, request.tool_name, request.tool_request_payload, IdempotencyKey(request.idempotency_key),
        LeaseToken(request.claim_token), request.capability,
    )


def to_agent_task_response(view: AgentTaskView) -> AgentTaskResponse:
    return AgentTaskResponse(
        agent_task_id=view.agent_task_id.value, workflow_instance_id=view.workflow_instance_id.value,
        task_key=view.task_key, state=view.state.name, task_version=view.task_version,
        claim_token=view.claim_token.value if view.claim_token else None, updated_at=view.updated_at,
        agent_role=view.agent_role, workflow_version=view.workflow_version,
    )


def to_claim_ready_response(views: list[AgentTaskView]) -> ClaimReadyAgentTasksResponse:
    return ClaimReadyAgentTasksResponse(tasks=[to_agent_task_response(view) for view in views])


def to_tool_request_response(view: ToolRequestView) -> ToolRequestResponse:
    return ToolRequestResponse(
        tool_request_id=view.tool_request_id.value, status=view.status.name, updated_at=view.updated_at, capability=view.capability
    )
