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


class RecoveryScanRequest(BaseModel):
    batch_size: int = 50


class RecoveryScanReportResponse(BaseModel):
    scanned: int
    checkpoint_inconsistent: int
    scanned_at: datetime


class LeaseRecoveryScanRequest(BaseModel):
    batch_size: int = 50


class LeaseRecoveryScanReportResponse(BaseModel):
    scanned: int
    retried: int
    staled: int
    scanned_at: datetime


class DispatchOutboxEventsRequest(BaseModel):
    batch_size: int = 50


class DispatchReportResponse(BaseModel):
    scanned: int
    published: int
    failed: int
    dead_lettered: int
    dispatched_at: datetime


class ReplayDeadLetterRequest(BaseModel):
    batch_size: int = 50


class DispatchToolRequestsRequest(BaseModel):
    batch_size: int = 50


class DispatchToolRequestsReportResponse(BaseModel):
    scanned: int
    dispatched: int
    dispatched_at: datetime


class CompleteWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)


class FailWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)
    failure_reason: str = Field(min_length=1)


class CancelWorkflowRequest(BaseModel):
    idempotency_key: str = Field(min_length=1)
    reason: str = Field(min_length=1)


class PoisonEventResponse(BaseModel):
    """SPEC-ARO-024 10-failure-handling §"Poison Event" step 4: "等待人工修复后 replay" —
    this is the visibility surface a human uses to see what needs fixing.
    """

    id: UUID
    event_id: str
    consumer_name: str
    event_type: str
    payload: str
    error_message: str
    occurred_at: datetime
    recorded_at: datetime
    # SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined".
    quarantined_at: datetime | None = None


class PoisonEventListResponse(BaseModel):
    poison_events: list[PoisonEventResponse]


class AuditEventResponse(BaseModel):
    """SPEC-ARO-034 12-observability §"Audit Events": the visibility surface an
    operator uses to see what has been recorded.
    """

    id: UUID
    audit_type: str
    action: str
    resource_type: str
    resource_id: str
    workflow_instance_id: UUID | None
    ticket_id: UUID | None
    actor_type: str
    actor_id: str | None
    outcome: str
    correlation_id: str | None
    causation_id: str | None
    detail: str
    occurred_at: datetime


class AuditEventListResponse(BaseModel):
    audit_events: list[AuditEventResponse]


class RetryAgentTaskRequest(BaseModel):
    """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task". No body fields
    needed today — the target task is named entirely by the path parameter — kept as
    its own request model (like RecoverWorkflowRequest()) so a future field doesn't
    require widening the route signature.
    """


class ForceRecoverWorkflowRequest(BaseModel):
    """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow"."""


class AgentTaskResponse(BaseModel):
    """Deliberately mirrors agentruntime.interfaces.worker.schemas.AgentTaskResponse
    rather than importing it — see WorkflowInstanceResponse's own docstring for why the
    admin module stays self-contained.
    """

    agent_task_id: UUID
    workflow_instance_id: UUID
    task_key: str
    state: str
    task_version: int
    updated_at: datetime
    agent_role: str | None = None


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
