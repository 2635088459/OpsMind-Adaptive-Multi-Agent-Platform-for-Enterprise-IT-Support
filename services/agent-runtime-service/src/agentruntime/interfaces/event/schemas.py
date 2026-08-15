"""SPEC-ARO-001 event-contract: "Event envelope must include correlationId, causationId,
ticketId, and workflowInstanceId."
"""

from __future__ import annotations

import json
from datetime import datetime
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

from pydantic import BaseModel, Field, model_validator


def _is_ticket_workflow_outbox_envelope(data: dict[str, Any]) -> bool:
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
        "event_type": _versioned_event_type(data, event_type),
        "producer": _first_present(data.get("producer"), "ticket-workflow-service"),
        "schema_version": _schema_version(data),
        "correlation_id": _uuid_or_deterministic(_first_present(data.get("correlation_id"), data.get("correlationId"), event_id), "correlation"),
        "causation_id": _uuid_or_deterministic(_first_present(data.get("causation_id"), data.get("causationId"), event_id), "causation"),
        "ticket_id": ticket_id,
        "occurred_at": _first_present(data.get("occurred_at"), data.get("occurredAt"), payload.get("createdAt"), payload.get("cancelledAt"), payload.get("reopenedAt")),
    }
    normalized.update(extra_fields)
    return normalized


class RuntimeEventRequest(BaseModel):
    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1)
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    workflow_instance_id: UUID
    expected_workflow_version: int | None = None
    occurred_at: datetime
    payload: str = Field(min_length=1)


class TicketCreatedEventRequest(BaseModel):
    """SPEC-ARO-005 06-event-contracts "ticket.created.v1" — no workflow_instance_id
    field: none exists yet, creating one is the point of this event.
    """

    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1, default="ticket.created.v1")
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    ticket_cycle_id: UUID
    priority: str = Field(min_length=1)
    category: str = Field(min_length=1)
    created_by: str = Field(min_length=1)
    occurred_at: datetime

    @model_validator(mode="before")
    @classmethod
    def accept_ticket_workflow_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_ticket_workflow_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        ticket_id = _first_present(raw.get("ticket_id"), raw.get("ticketId"), raw.get("aggregateId"), payload.get("ticketId"))
        return _normalize_ticket_workflow_envelope(raw, "ticket.created.v1", {
            # SPEC-TW-008/PUB ticket.created does not expose a resolutionCycleId yet; the
            # initial Runtime cycle is deterministic so redelivery and retries stay idempotent.
            "ticket_cycle_id": _first_present(raw.get("ticket_cycle_id"), raw.get("ticketCycleId"), payload.get("ticketCycleId"), ticket_id),
            "priority": _first_present(payload.get("priority"), "UNSPECIFIED"),
            "category": _first_present(payload.get("category"), payload.get("applicationCode"), payload.get("source"), "UNKNOWN"),
            "created_by": _first_present(payload.get("createdBy"), payload.get("requesterIdHash"), raw.get("producer"), "ticket-workflow-service"),
        })


class TicketCancelledEventRequest(BaseModel):
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-014 "ticket.cancelled.v1")
    — no workflow_instance_id field, for the same reason TicketCreatedEventRequest has
    none: Ticket Workflow has no knowledge of this Runtime's own internal id.
    """

    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1, default="ticket.cancelled.v1")
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    ticket_cycle_id: UUID
    cancel_reason_code: str = Field(min_length=1)
    occurred_at: datetime

    @model_validator(mode="before")
    @classmethod
    def accept_ticket_workflow_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_ticket_workflow_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_ticket_workflow_envelope(raw, "ticket.cancelled.v1", {
            "ticket_cycle_id": _first_present(raw.get("ticket_cycle_id"), raw.get("ticketCycleId"), payload.get("ticketCycleId"), payload.get("resolutionCycleId")),
            "cancel_reason_code": _first_present(raw.get("cancel_reason_code"), raw.get("cancelReasonCode"), payload.get("cancelReasonCode")),
        })


class TicketReopenedEventRequest(BaseModel):
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-015 "ticket.reopened.v1")."""

    event_id: str = Field(min_length=1)
    event_type: str = Field(min_length=1, default="ticket.reopened.v1")
    producer: str = Field(min_length=1)
    schema_version: int = Field(ge=1)
    correlation_id: UUID
    causation_id: UUID
    ticket_id: UUID
    previous_ticket_cycle_id: UUID
    new_ticket_cycle_id: UUID
    reason_code: str = Field(min_length=1)
    occurred_at: datetime

    @model_validator(mode="before")
    @classmethod
    def accept_ticket_workflow_outbox_envelope(cls, raw: Any) -> Any:
        if not isinstance(raw, dict) or not _is_ticket_workflow_outbox_envelope(raw):
            return raw

        payload = _payload(raw)
        return _normalize_ticket_workflow_envelope(raw, "ticket.reopened.v1", {
            "previous_ticket_cycle_id": _first_present(
                raw.get("previous_ticket_cycle_id"), raw.get("previousTicketCycleId"), payload.get("previousTicketCycleId"),
                payload.get("previousResolutionCycleId"),
            ),
            "new_ticket_cycle_id": _first_present(
                raw.get("new_ticket_cycle_id"), raw.get("newTicketCycleId"), payload.get("newTicketCycleId"),
                payload.get("newResolutionCycleId"),
            ),
            "reason_code": _first_present(raw.get("reason_code"), raw.get("reasonCode"), payload.get("reasonCode"), payload.get("reopenReasonCode")),
        })
