"""SPEC-ARO-035 14-testing-strategy §"契约测试" names exactly ten event types that need
contract validation:

    Consumed:  ticket.created.v1, approval.granted.v1, tool.completed.v1,
               verification.completed.v1
    Published: workflow.started.v1, workflow.paused.v1, workflow.resumed.v1,
               agent.task.completed.v1, workflow.completed.v1, workflow.failed.v1

06-event-contracts documents the outer Envelope shape literally (see EnvelopeContract
below) and, for the four consumed event types, an explicit "关键字段" (key fields) list
per type — that list is this codebase's own ground truth for what a producer must send,
so each ...PayloadContract below is a direct transcription of it. 06-event-contracts does
NOT list inner-payload fields for the six published event types (only the trigger
condition each is published on) — for those, the contract is this service's own promise
to its downstream consumers, so each payload contract is instead a direct transcription
of what the real, already-shipping application service actually places in
OutboxRecord.payload (start_workflow.py/pause_workflow.py/resume_workflow.py/
complete_workflow.py/fail_workflow.py/complete_agent_task.py's own `_to_payload`/
`_to_outbox` methods) — test_published_event_contracts.py drives those real services and
validates their real output against these models, so any future drift between the code
and this contract fails loudly instead of silently.

Field requiredness rule: a documented key field is modelled as required UNLESS the
current, already-shipping consumer only ever reads it via `dict.get(...)` (tool.completed
.v1's `resultPayload`, verification.completed.v1's `evidence`) — in which case it is
modelled Optional, matching what this codebase already tolerates from a real producer
today. Everything else in 06-event-contracts' own key-field lists is required, even where
the current consumer does not happen to read it (e.g. tool.completed.v1's
`gatewayCorrelationId`/`agentTaskId`), since the contract describes what a producer must
send, not merely what today's consumer bothers to extract.

Wire naming: the four consumed event types' inner `payload` JSON strings are opaque as
far as the outer envelope's own Pydantic validation is concerned (interfaces/event/
schemas.py's RuntimeEventRequest.payload is a plain `str`) — every consuming service
(consume_approval.py, consume_tool_result.py, consume_verification.py) parses that inner
JSON via raw `payload["camelCaseKey"]` indexing, so the camelCase names below match
06-event-contracts' own documented field names exactly. ticket.created.v1 is different:
it has its own dedicated FastAPI route/Pydantic model (TicketCreatedEventRequest) that
IS already the live contract for that event type, validated snake_case at the HTTP
boundary the same way every other interfaces/*/schemas.py model in this codebase is —
reused directly below rather than re-modelled, since duplicating it would just be a
second copy that could silently drift from the one FastAPI actually validates against.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from agentruntime.interfaces.event.schemas import TicketCreatedEventRequest

# ---------------------------------------------------------------------------
# Shared Envelope (06-event-contracts §"Envelope")
# ---------------------------------------------------------------------------


class EnvelopeContract(BaseModel):
    eventId: str = Field(min_length=1)
    eventType: str = Field(min_length=1)
    aggregateId: str = Field(min_length=1)
    ticketId: str = Field(min_length=1)
    correlationId: str = Field(min_length=1)
    causationId: str = Field(min_length=1)
    occurredAt: str = Field(min_length=1)
    payload: dict[str, object]


# ---------------------------------------------------------------------------
# Consumed event payload contracts (06-event-contracts §"消费事件")
# ---------------------------------------------------------------------------

# ticket.created.v1 reuses the real, live-validated model directly.
TicketCreatedContract = TicketCreatedEventRequest


class ApprovalGrantedPayloadContract(BaseModel):
    """approval.granted.v1 — consumed via the generic RuntimeEventRequest envelope;
    this models its `payload` JSON string (consume_approval.py's own apply()).
    """

    approvalRequestId: str = Field(min_length=1)
    ticketId: str = Field(min_length=1)
    workflowInstanceId: str = Field(min_length=1)
    decision: str = Field(min_length=1)
    approvedBy: str = Field(min_length=1)
    occurredAt: datetime


class ToolCompletedPayloadContract(BaseModel):
    """tool.completed.v1 — consume_tool_result.py's own apply(). `resultPayload` is
    Optional: that service reads it via `payload.get("resultPayload")`, already
    tolerating its absence (e.g. a FAILED status with no result body).
    """

    toolRequestId: str = Field(min_length=1)
    gatewayCorrelationId: str = Field(min_length=1)
    workflowInstanceId: str = Field(min_length=1)
    agentTaskId: str = Field(min_length=1)
    status: str = Field(min_length=1)
    occurredAt: datetime
    resultPayload: str | None = None


class VerificationCompletedPayloadContract(BaseModel):
    """verification.completed.v1 — consume_verification.py's own apply(). `evidence` is
    Optional: that service reads it via `payload.get("evidence")`.
    """

    verificationRequestId: str = Field(min_length=1)
    workflowInstanceId: str = Field(min_length=1)
    ticketId: str = Field(min_length=1)
    passed: bool
    occurredAt: datetime
    evidence: str | None = None


# ---------------------------------------------------------------------------
# Published event payload contracts (06-event-contracts §"发布事件") — transcribed
# from each publishing service's own _to_payload()/_to_outbox() today.
# ---------------------------------------------------------------------------


class WorkflowStartedPayloadContract(BaseModel):
    """workflow.started.v1 — start_workflow.py's own _to_payload()."""

    workflowInstanceId: str = Field(min_length=1)
    ticketId: str = Field(min_length=1)
    ticketCycleId: str = Field(min_length=1)
    workflowType: str = Field(min_length=1)
    definitionId: str = Field(min_length=1)
    definitionVersion: int
    toState: str = Field(min_length=1)
    workflowVersion: int
    occurredAt: str = Field(min_length=1)


class WorkflowPausedPayloadContract(BaseModel):
    """workflow.paused.v1 — pause_workflow.py's own _to_payload()."""

    workflowInstanceId: str = Field(min_length=1)
    toState: str = Field(min_length=1)
    workflowVersion: int
    pauseGeneration: int
    idempotencyKey: str = Field(min_length=1)
    occurredAt: str = Field(min_length=1)
    fromState: str | None = None


class WorkflowResumedPayloadContract(BaseModel):
    """workflow.resumed.v1 — resume_workflow.py's own _to_payload() (same shape as
    workflow.paused.v1's own payload; resume is pause's mirror-image transition).
    """

    workflowInstanceId: str = Field(min_length=1)
    toState: str = Field(min_length=1)
    workflowVersion: int
    pauseGeneration: int
    idempotencyKey: str = Field(min_length=1)
    occurredAt: str = Field(min_length=1)
    fromState: str | None = None


class WorkflowCompletedPayloadContract(BaseModel):
    """workflow.completed.v1 — complete_workflow.py's own _to_payload()."""

    workflowInstanceId: str = Field(min_length=1)
    toState: str = Field(min_length=1)
    workflowVersion: int
    occurredAt: str = Field(min_length=1)
    fromState: str | None = None


class WorkflowFailedPayloadContract(BaseModel):
    """workflow.failed.v1 — fail_workflow.py's own _to_payload()."""

    workflowInstanceId: str = Field(min_length=1)
    toState: str = Field(min_length=1)
    workflowVersion: int
    failureReason: str = Field(min_length=1)
    occurredAt: str = Field(min_length=1)
    fromState: str | None = None


class AgentTaskCompletedPayloadContract(BaseModel):
    """agent.task.completed.v1 — complete_agent_task.py's own _to_outbox(), the
    COMPLETED branch. 06-event-contracts: "事件包含 result summary，不包含敏感原始上下文" —
    resultPayload is always present on this branch (unlike agent.task.failed.v1's own
    failureReason-only shape, which is a distinct event type this spec's own
    契约测试 list does not name).
    """

    agentTaskId: str = Field(min_length=1)
    workflowInstanceId: str = Field(min_length=1)
    toState: str = Field(min_length=1)
    taskVersion: int
    occurredAt: str = Field(min_length=1)
    resultPayload: str
