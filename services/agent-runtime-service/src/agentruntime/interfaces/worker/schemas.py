from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, model_validator


class ClaimAgentTaskRequest(BaseModel):
    workflow_instance_id: UUID
    task_key: str = Field(min_length=1)
    worker_id: str = Field(min_length=1)
    lease_seconds: int = Field(ge=1)


class ClaimReadyAgentTasksRequest(BaseModel):
    """SPEC-ARO-009 05-api-contracts "Claim Task": "Worker provides agentRole, workerId,
    and maxTasks."
    """

    agent_role: str = Field(min_length=1)
    worker_id: str = Field(min_length=1)
    max_tasks: int = Field(ge=1)
    lease_seconds: int = Field(ge=1)


class CompleteAgentTaskRequest(BaseModel):
    claim_token: UUID
    idempotency_key: str = Field(min_length=1)
    # SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version".
    workflow_version: int
    result_payload: str | None = None
    failure_reason: str | None = None

    @model_validator(mode="after")
    def _exactly_one_outcome(self) -> "CompleteAgentTaskRequest":
        has_result = bool(self.result_payload and self.result_payload.strip())
        has_failure = bool(self.failure_reason and self.failure_reason.strip())
        if has_result == has_failure:
            raise ValueError("exactly one of result_payload or failure_reason must be provided")
        return self


class RequestToolRequest(BaseModel):
    workflow_instance_id: UUID
    agent_task_id: UUID
    checkpoint_payload: str = Field(min_length=1)
    tool_name: str = Field(min_length=1)
    tool_request_payload: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1)
    # SPEC-ARO-018 11-security §"Authorization": proves the caller actually holds this
    # task's claim — the same claimToken claim/complete already require.
    claim_token: UUID
    # SPEC-ARO-017 01-domain-model: one of Tool Request's own minimal fields, alongside
    # tool_name. Still optional — skips authorization entirely when absent; when
    # present, checked against the claiming Agent Task's own agent_role
    # (SPEC-ARO-032 11-security §"Tool Gateway 强制路径").
    capability: str | None = None


class AgentTaskResponse(BaseModel):
    agent_task_id: UUID
    workflow_instance_id: UUID
    task_key: str
    state: str
    task_version: int
    claim_token: UUID | None
    updated_at: datetime
    agent_role: str | None = None
    # SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version": the worker must
    # hold onto this and resubmit it on POST /agent-tasks/{id}/complete. None for a
    # response that never went through a workflow lookup (e.g. a plain query).
    workflow_version: int | None = None


class ClaimReadyAgentTasksResponse(BaseModel):
    tasks: list[AgentTaskResponse]


class ToolRequestResponse(BaseModel):
    tool_request_id: UUID
    status: str
    updated_at: datetime
    capability: str | None = None
