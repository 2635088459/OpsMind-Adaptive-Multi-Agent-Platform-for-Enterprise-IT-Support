"""SPEC-MK-010 06-event-contracts: request shapes for the ticket-memory-source event
listener. Manual/ops trigger until a real RabbitMQ async consumer exists — mirrors
agent-runtime-service's own interfaces/event/router.py precedent exactly (that
module's own docstring: "the seam that consumer will call into instead of a client
hitting it directly").

Accepts two shapes on the same route: (1) a clean, already-normalized snake_case body
(what a hand-written test or a future RabbitMQ adapter would construct), and (2) the
raw 02-ticket-workflow outbox envelope as that service's own OutboxEventEntry/
TicketResolvedEventMapper/TicketClosedEventMapper actually produce it (camelCase,
nested `payload`, `routingKey` carrying the versioned event type) — "02 remains
system of record" (this spec's own domain-rule) means 02's real wire shape must be
accepted as-is, not only a simplified test fixture.
"""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

from pydantic import BaseModel, Field, model_validator


def _is_raw_outbox_envelope(data: dict[str, Any]) -> bool:
    """Matches both upstream services' own raw outbox envelope shapes — 02-ticket-
    workflow's (eventId/eventType/eventVersion/routingKey/aggregateId/payload) and
    03-agent-runtime-orchestration's (eventId/eventType/aggregateId/ticketId/
    correlationId/causationId/occurredAt/payload, no eventVersion/routingKey) — a
    request that is neither is assumed to already be this route's own clean
    snake_case shape.
    """
    return any(key in data for key in ("eventId", "eventType", "eventVersion", "routingKey", "aggregateId"))


def _payload(data: dict[str, Any]) -> dict[str, Any]:
    payload = data.get("payload")
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, str) and payload.strip():
        decoded = json.loads(payload)
        if isinstance(decoded, dict):
            return decoded
    return {}


def _first_present(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _schema_version(data: dict[str, Any]) -> int | None:
    version = _first_present(data.get("schema_version"), data.get("schemaVersion"))
    if version is not None:
        return version
    event_version = data.get("eventVersion")
    if isinstance(event_version, str) and event_version.strip():
        major = event_version.split(".", 1)[0]
        if major.isdigit():
            return int(major)
    return None


def _versioned_event_type(data: dict[str, Any], default: str) -> str:
    routing_key = data.get("routingKey")
    if isinstance(routing_key, str) and routing_key.strip():
        return routing_key

    event_type = _first_present(data.get("event_type"), data.get("eventType"))
    if isinstance(event_type, str) and event_type.strip():
        if event_type.rsplit(".", 1)[-1].startswith("v"):
            return event_type
        version = _schema_version(data)
        if version is not None:
            return f"{event_type}.v{version}"
        return event_type
    return default


def _uuid_or_deterministic(value: Any, namespace_seed: str) -> str | None:
    if value is None:
        return None
    raw = str(value)
    try:
        return str(UUID(raw))
    except ValueError:
        return str(uuid5(NAMESPACE_URL, f"opsmind:{namespace_seed}:{raw}"))


def _normalize_ticket_workflow_envelope(data: dict[str, Any], event_type: str, extra_fields: dict[str, Any]) -> dict[str, Any]:
    payload = _payload(data)
    ticket_id = _first_present(data.get("ticket_id"), data.get("ticketId"), data.get("aggregateId"), payload.get("ticketId"))
    event_id = _first_present(data.get("event_id"), data.get("eventId"))

    normalized = {
        **data,
        "event_id": event_id,
        "ticket_id": ticket_id,
        "correlation_id": _uuid_or_deterministic(_first_present(data.get("correlation_id"), data.get("correlationId"), event_id), "correlation"),
    }
    normalized.update(extra_fields)
    return normalized


def _normalize_agent_runtime_envelope(data: dict[str, Any], extra_fields: dict[str, Any]) -> dict[str, Any]:
    payload = _payload(data)
    event_id = _first_present(data.get("event_id"), data.get("eventId"))
    workflow_instance_id = _first_present(
        data.get("workflow_instance_id"), data.get("workflowInstanceId"), data.get("aggregateId"), payload.get("workflowInstanceId"),
    )
    ticket_id = _first_present(data.get("ticket_id"), data.get("ticketId"), payload.get("ticketId"))

    normalized = {
        **data,
        "event_id": event_id,
        "workflow_instance_id": workflow_instance_id,
        "ticket_id": ticket_id,
        "correlation_id": _uuid_or_deterministic(_first_present(data.get("correlation_id"), data.get("correlationId"), event_id), "correlation"),
    }
    normalized.update(extra_fields)
    return normalized


class TicketResolvedEventRequest(BaseModel):
    """SPEC-MK-010 06-event-contracts (02-ticket-workflow PUB-012 "ticket.resolved.v1").
    Field names match that service's own real published payload
    (TicketResolvedEventMapper): resolutionCode/resolutionSummary/resolvedBy/resolvedAt,
    plus resolutionCycleId which 04's own aggregates call ticket_cycle_id.
    """

    event_id: str = Field(min_length=1)
    ticket_id: UUID
    ticket_cycle_id: UUID
    resolution_code: str = Field(min_length=1)
    resolution_summary: str = Field(min_length=1)
    resolved_by: str = Field(min_length=1)
    resolved_at: datetime
    correlation_id: UUID

    @model_validator(mode="before")
    @classmethod
    def accept_ticket_workflow_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_raw_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_ticket_workflow_envelope(raw, "ticket.resolved.v1", {
            "ticket_cycle_id": _first_present(raw.get("ticket_cycle_id"), raw.get("ticketCycleId"), payload.get("resolutionCycleId")),
            "resolution_code": _first_present(raw.get("resolution_code"), payload.get("resolutionCode")),
            "resolution_summary": _first_present(raw.get("resolution_summary"), payload.get("resolutionSummary")),
            "resolved_by": _first_present(raw.get("resolved_by"), payload.get("resolvedBy")),
            "resolved_at": _first_present(raw.get("resolved_at"), payload.get("resolvedAt")),
        })


class TicketClosedEventRequest(BaseModel):
    """SPEC-MK-010 06-event-contracts (02-ticket-workflow PUB-013 "ticket.closed.v1")."""

    event_id: str = Field(min_length=1)
    ticket_id: UUID
    ticket_cycle_id: UUID
    close_reason_code: str = Field(min_length=1)
    close_reason: str = Field(min_length=1)
    closed_by: str = Field(min_length=1)
    closed_at: datetime
    correlation_id: UUID

    @model_validator(mode="before")
    @classmethod
    def accept_ticket_workflow_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_raw_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_ticket_workflow_envelope(raw, "ticket.closed.v1", {
            "ticket_cycle_id": _first_present(raw.get("ticket_cycle_id"), raw.get("ticketCycleId"), payload.get("resolutionCycleId")),
            "close_reason_code": _first_present(raw.get("close_reason_code"), payload.get("closeReasonCode")),
            "close_reason": _first_present(raw.get("close_reason"), payload.get("closeReason"), payload.get("closeReasonCode")),
            "closed_by": _first_present(raw.get("closed_by"), payload.get("closedBy")),
            "closed_at": _first_present(raw.get("closed_at"), payload.get("closedAt")),
        })


class WorkflowCompletedEventRequest(BaseModel):
    """SPEC-MK-022 06-event-contracts (03-agent-runtime-orchestration's own real
    published `workflow.completed.v1` — CompleteWorkflowService._to_payload):
    workflowInstanceId/fromState/toState/workflowVersion/occurredAt. No
    ticketCycleId anywhere on the real event — that service's own OutboxRecord
    never carries one.
    """

    event_id: str = Field(min_length=1)
    workflow_instance_id: UUID
    ticket_id: UUID
    from_state: str | None = None
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    occurred_at: datetime
    correlation_id: UUID

    @model_validator(mode="before")
    @classmethod
    def accept_agent_runtime_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_raw_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_agent_runtime_envelope(raw, {
            "from_state": _first_present(raw.get("from_state"), payload.get("fromState")),
            "to_state": _first_present(raw.get("to_state"), payload.get("toState")),
            "workflow_version": _first_present(raw.get("workflow_version"), payload.get("workflowVersion")),
            "occurred_at": _first_present(raw.get("occurred_at"), payload.get("occurredAt"), raw.get("occurredAt")),
        })


class WorkflowFailedEventRequest(BaseModel):
    """SPEC-MK-022 06-event-contracts (03-agent-runtime-orchestration's own real
    published `workflow.failed.v1` — FailWorkflowService._to_payload): adds
    failureReason to WorkflowCompletedEventRequest's own shape.
    """

    event_id: str = Field(min_length=1)
    workflow_instance_id: UUID
    ticket_id: UUID
    from_state: str | None = None
    to_state: str = Field(min_length=1)
    workflow_version: int = Field(ge=1)
    failure_reason: str = Field(min_length=1)
    occurred_at: datetime
    correlation_id: UUID

    @model_validator(mode="before")
    @classmethod
    def accept_agent_runtime_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_raw_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_agent_runtime_envelope(raw, {
            "from_state": _first_present(raw.get("from_state"), payload.get("fromState")),
            "to_state": _first_present(raw.get("to_state"), payload.get("toState")),
            "workflow_version": _first_present(raw.get("workflow_version"), payload.get("workflowVersion")),
            "failure_reason": _first_present(raw.get("failure_reason"), payload.get("failureReason")),
            "occurred_at": _first_present(raw.get("occurred_at"), payload.get("occurredAt"), raw.get("occurredAt")),
        })
