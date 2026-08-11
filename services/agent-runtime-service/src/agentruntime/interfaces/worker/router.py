"""13-package-and-class-design §"Interfaces": "Worker task endpoint" — the surface
Agent Workers use to claim and complete tasks, and to request a Tool side effect
through the Tool Gateway. Depends only on AgentTaskCommandPort; never on
ToolGatewayPort directly (02-business-invariants §"Tool Gateway Boundary").
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends

from agentruntime.application.ports_in import AgentTaskCommandPort, AgentTaskQueryPort
from agentruntime.container import get_agent_task_command_port, get_agent_task_query_port
from agentruntime.domain.ids import AgentTaskId
from agentruntime.interfaces.worker.mapper import (
    to_agent_task_response,
    to_claim_command,
    to_claim_ready_command,
    to_claim_ready_response,
    to_complete_command,
    to_request_tool_command,
    to_tool_request_response,
)
from agentruntime.interfaces.worker.schemas import (
    AgentTaskResponse,
    ClaimAgentTaskRequest,
    ClaimReadyAgentTasksRequest,
    ClaimReadyAgentTasksResponse,
    CompleteAgentTaskRequest,
    RequestToolRequest,
    ToolRequestResponse,
)

router = APIRouter(prefix="/internal/agent-runtime/v1/agent-tasks", tags=["agent-tasks"])


@router.post("/claim", response_model=AgentTaskResponse)
def claim_agent_task(request: ClaimAgentTaskRequest, port: AgentTaskCommandPort = Depends(get_agent_task_command_port)) -> AgentTaskResponse:
    return to_agent_task_response(port.claim_agent_task(to_claim_command(request)))


@router.post("/claim-ready", response_model=ClaimReadyAgentTasksResponse)
def claim_ready_agent_tasks(
    request: ClaimReadyAgentTasksRequest, port: AgentTaskCommandPort = Depends(get_agent_task_command_port)
) -> ClaimReadyAgentTasksResponse:
    """SPEC-ARO-009 05-api-contracts "Claim Task": the role-based batch endpoint a
    generic Agent Worker pool polls with — claim_agent_task above stays for a caller
    that already knows exactly which task it wants.
    """
    return to_claim_ready_response(port.claim_ready_agent_tasks(to_claim_ready_command(request)))


@router.post("/{agent_task_id}/complete", response_model=AgentTaskResponse)
def complete_agent_task(
    agent_task_id: UUID, request: CompleteAgentTaskRequest, port: AgentTaskCommandPort = Depends(get_agent_task_command_port)
) -> AgentTaskResponse:
    return to_agent_task_response(port.complete_agent_task(to_complete_command(agent_task_id, request)))


@router.post("/request-tool", response_model=ToolRequestResponse)
def request_tool(request: RequestToolRequest, port: AgentTaskCommandPort = Depends(get_agent_task_command_port)) -> ToolRequestResponse:
    return to_tool_request_response(port.request_tool(to_request_tool_command(request)))


@router.get("/{agent_task_id}", response_model=AgentTaskResponse)
def find_agent_task(agent_task_id: UUID, port: AgentTaskQueryPort = Depends(get_agent_task_query_port)) -> AgentTaskResponse:
    """SPEC-ARO-006 05-api-contracts "GET /tasks/{agentTaskId}"."""
    return to_agent_task_response(port.find_agent_task(AgentTaskId(agent_task_id)))
