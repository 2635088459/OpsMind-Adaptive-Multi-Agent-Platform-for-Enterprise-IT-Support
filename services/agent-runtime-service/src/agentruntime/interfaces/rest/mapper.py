"""Mapping boundary for the Workflow Instance REST surface — only ever builds
application-layer commands/DTOs, never agentruntime.domain types (enforced by
tests/architecture, mirroring the Java siblings' LayerDependencyTest).
"""

from __future__ import annotations

from uuid import UUID

from agentruntime.application.commands import PauseWorkflowCommand, ResumeWorkflowCommand, StartWorkflowCommand, TaskNodeInput, WorkflowDefinitionInput
from agentruntime.application.views import CheckpointView, WorkflowInstanceView
from agentruntime.domain.ids import IdempotencyKey, TicketCycleId, TicketId, WorkflowInstanceId
from agentruntime.interfaces.rest.schemas import CheckpointResponse, PauseWorkflowRequest, ResumeWorkflowRequest, StartWorkflowRequest, WorkflowResponse


def to_start_command(request: StartWorkflowRequest) -> StartWorkflowCommand:
    nodes = tuple(
        TaskNodeInput(node.task_key, node.task_type, frozenset(node.depends_on), node.join_policy, node.agent_role)
        for node in request.task_graph
    )
    definition = WorkflowDefinitionInput(request.workflow_definition_id, request.definition_version, request.workflow_type, nodes)
    return StartWorkflowCommand(
        TicketId(request.ticket_id), TicketCycleId(request.ticket_cycle_id), definition, IdempotencyKey(request.idempotency_key)
    )


def to_pause_command(workflow_instance_id: UUID, request: PauseWorkflowRequest) -> PauseWorkflowCommand:
    return PauseWorkflowCommand(WorkflowInstanceId(workflow_instance_id), IdempotencyKey(request.idempotency_key))


def to_resume_command(workflow_instance_id: UUID, request: ResumeWorkflowRequest) -> ResumeWorkflowCommand:
    return ResumeWorkflowCommand(WorkflowInstanceId(workflow_instance_id), IdempotencyKey(request.idempotency_key))


def to_response(view: WorkflowInstanceView) -> WorkflowResponse:
    return WorkflowResponse(
        workflow_instance_id=view.workflow_instance_id.value, state=view.state.name,
        workflow_version=view.workflow_version, pause_generation=view.pause_generation, updated_at=view.updated_at,
    )


def to_checkpoint_response(view: CheckpointView) -> CheckpointResponse:
    return CheckpointResponse(
        checkpoint_id=view.checkpoint_id.value, workflow_instance_id=view.workflow_instance_id.value,
        type=view.type.name, schema_version=view.schema_version, payload=view.payload, recorded_at=view.recorded_at,
        workflow_version=view.workflow_version, checksum=view.checksum, cursor=view.cursor,
    )
