from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class RecoverWorkflowRequest(BaseModel):
    expected_definition_version: int | None = None


class RecoveryReportResponse(BaseModel):
    workflow_instance_id: UUID
    state: str
    workflow_version: int
    definition_version: int
    recoverable_checkpoint_count: int
    open_lease_count: int
    recovered_at: datetime


class DispatchOutboxEventsRequest(BaseModel):
    batch_size: int = 50


class DispatchReportResponse(BaseModel):
    scanned: int
    published: int
    failed: int
    dead_lettered: int
    dispatched_at: datetime


class CompleteWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)


class FailWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)
    failure_reason: str = Field(min_length=1)


class CancelWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)
    reason: str = Field(min_length=1)


class WorkflowInstanceResponse(BaseModel):
    """SPEC-ARO-004: response shape for the admin-triggered terminal transitions.
    Deliberately mirrors agentruntime.interfaces.rest.schemas.WorkflowResponse rather
    than importing it — the admin module stays self-contained the way it already was
    before this spec, and the two surfaces are free to diverge later (e.g. an
    admin-only audit field) without a shared-schema coupling.
    """

    workflow_instance_id: UUID
    state: str
    workflow_version: int
    pause_generation: int
    updated_at: datetime
