"""13-package-and-class-design §"Interfaces": internal REST controller for Workflow
Instance commands. Depends only on WorkflowCommandPort; never touches a repository or
another service directly.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, status

from agentruntime.application.ports_in import WorkflowCommandPort, WorkflowQueryPort
from agentruntime.container import get_workflow_command_port, get_workflow_query_port
from agentruntime.domain.ids import TicketId, WorkflowInstanceId
from agentruntime.interfaces.rest.mapper import to_checkpoint_response, to_pause_command, to_response, to_resume_command, to_start_command
from agentruntime.interfaces.rest.schemas import CheckpointResponse, PauseWorkflowRequest, ResumeWorkflowRequest, StartWorkflowRequest, WorkflowResponse

router = APIRouter(prefix="/internal/agent-runtime/v1/workflows", tags=["workflows"])


@router.post("", status_code=status.HTTP_201_CREATED, response_model=WorkflowResponse)
def start_workflow(request: StartWorkflowRequest, port: WorkflowCommandPort = Depends(get_workflow_command_port)) -> WorkflowResponse:
    return to_response(port.start_workflow(to_start_command(request)))


@router.post("/{workflow_instance_id}/pause", response_model=WorkflowResponse)
def pause_workflow(
    workflow_instance_id: UUID, request: PauseWorkflowRequest, port: WorkflowCommandPort = Depends(get_workflow_command_port)
) -> WorkflowResponse:
    return to_response(port.pause_workflow(to_pause_command(workflow_instance_id, request)))


@router.post("/{workflow_instance_id}/resume", response_model=WorkflowResponse)
def resume_workflow(
    workflow_instance_id: UUID, request: ResumeWorkflowRequest, port: WorkflowCommandPort = Depends(get_workflow_command_port)
) -> WorkflowResponse:
    return to_response(port.resume_workflow(to_resume_command(workflow_instance_id, request)))


@router.get("/by-ticket/{ticket_id}", response_model=list[WorkflowResponse])
def find_workflow_instances_by_ticket(ticket_id: UUID, port: WorkflowQueryPort = Depends(get_workflow_query_port)) -> list[WorkflowResponse]:
    """SPEC-ARO-006 05-api-contracts "GET /workflows/by-ticket/{ticketId}"."""
    return [to_response(view) for view in port.find_workflow_instances_by_ticket(TicketId(ticket_id))]


@router.get("/{workflow_instance_id}", response_model=WorkflowResponse)
def find_workflow_instance(workflow_instance_id: UUID, port: WorkflowQueryPort = Depends(get_workflow_query_port)) -> WorkflowResponse:
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}"."""
    return to_response(port.find_workflow_instance(WorkflowInstanceId(workflow_instance_id)))


@router.get("/{workflow_instance_id}/checkpoints/latest", response_model=CheckpointResponse)
def find_latest_checkpoint(workflow_instance_id: UUID, port: WorkflowQueryPort = Depends(get_workflow_query_port)) -> CheckpointResponse:
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/latest"."""
    return to_checkpoint_response(port.find_latest_checkpoint(WorkflowInstanceId(workflow_instance_id)))
