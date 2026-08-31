"""SPEC-EI-030/SPEC-EI-031/SPEC-EI-032 06-event-contracts: request shapes for the
cross-domain event listener. Manual/ops trigger until a real RabbitMQ async consumer
exists — mirrors memory-knowledge-service's own interfaces/event/schemas.py precedent
(see that module's own docstring: "the seam that consumer will call into instead of a
client hitting it directly").

Field names are transcribed from each real producer's own actual published payload —
02-ticket-workflow's TicketResolvedEventMapper/TicketReopenedEventMapper,
03-agent-runtime-orchestration's CompleteWorkflowService/FailWorkflowService,
05-tool-integration-gateway's outbox_events.py, 06-policy-approval-governance's
ApprovalGrantedEvent/ApprovalDeniedEvent — not this domain's own illustrative
06-event-contracts sketch (interfaces.event's own module docstring already flagged
several of these as guesses that diverge from the real thing). `event_id` and
`correlation_id` are required on every one of these (phase-07's own "强制约束": "所有
cross-domain payload 必须有 version、correlation id 和 PII classification") — pydantic's
own `Field(min_length=1)`/no-default enforces that a malformed inbound payload missing
either is rejected with `400 VALIDATION_ERROR`, not silently accepted.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class TicketResolvedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    ticket_id: str = Field(min_length=1)
    resolution_code: str = Field(min_length=1)
    resolution_summary: str = ""
    resolved_at: datetime
    correlation_id: str = Field(min_length=1)


class TicketReopenedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    ticket_id: str = Field(min_length=1)
    reopen_reason_code: str = Field(min_length=1)
    reopen_count: int = Field(ge=1)
    reopened_at: datetime
    correlation_id: str = Field(min_length=1)


class WorkflowCompletedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    workflow_instance_id: str = Field(min_length=1)
    ticket_id: str = Field(min_length=1)
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    occurred_at: datetime
    correlation_id: str = Field(min_length=1)


class WorkflowFailedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    workflow_instance_id: str = Field(min_length=1)
    ticket_id: str = Field(min_length=1)
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    failure_reason: str = Field(min_length=1)
    occurred_at: datetime
    correlation_id: str = Field(min_length=1)


class ToolCompletedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    tool_request_id: str = Field(min_length=1)
    capability_name: str = Field(min_length=1)
    status: str = Field(min_length=1)
    redaction_status: str | None = None
    error_code: str | None = None
    occurred_at: datetime
    correlation_id: str = Field(min_length=1)


class MemoryRetrievalCompletedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    query_id: str = Field(min_length=1)
    memory_type: str = Field(min_length=1)
    result_count: int = Field(ge=0)
    acl_scope_denied: bool = False
    occurred_at: datetime
    correlation_id: str = Field(min_length=1)


class ApprovalGrantedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    approval_request_id: str = Field(min_length=1)
    source_domain: str = Field(min_length=1)
    source_request_id: str = Field(min_length=1)
    decided_by: str = Field(min_length=1)
    correlation_id: str = Field(min_length=1)


class ApprovalDeniedEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    approval_request_id: str = Field(min_length=1)
    source_domain: str = Field(min_length=1)
    source_request_id: str = Field(min_length=1)
    decided_by: str = Field(min_length=1)
    reason: str = ""
    correlation_id: str = Field(min_length=1)
