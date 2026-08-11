"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules. They only perform request validation, auth, and DTO mapping." pydantic request/
response models for the Workflow Instance REST surface.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class TaskNodeRequest(BaseModel):
    task_key: str = Field(min_length=1)
    task_type: str = Field(min_length=1)
    depends_on: list[str] = Field(default_factory=list)
    join_policy: str = Field(min_length=1)
    # SPEC-ARO-009: see domain.task_graph.TaskNode.agent_role's docstring.
    agent_role: str | None = None


class StartWorkflowRequest(BaseModel):
    ticket_id: UUID
    ticket_cycle_id: UUID
    workflow_definition_id: str = Field(min_length=1)
    definition_version: int = Field(ge=1)
    workflow_type: str = Field(min_length=1)
    task_graph: list[TaskNodeRequest] = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)


class PauseWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)


class ResumeWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)


class WorkflowResponse(BaseModel):
    workflow_instance_id: UUID
    state: str
    workflow_version: int
    pause_generation: int
    updated_at: datetime


class CheckpointResponse(BaseModel):
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/latest"."""

    checkpoint_id: UUID
    workflow_instance_id: UUID
    type: str
    schema_version: int
    payload: str
    recorded_at: datetime
