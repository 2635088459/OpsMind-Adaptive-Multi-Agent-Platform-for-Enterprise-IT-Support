"""Encodes a memoryknowledge.domain.events.* dataclass into an
memoryknowledge.application.records.OutboxRecord. Pure stdlib (json/dataclasses/uuid) —
lives in application, not infrastructure, because every application service that
publishes an event needs it and application must not depend on infrastructure (the
import-linter "forbidden" contract). SPEC-MK-001 domain-rules: "事件发布必须经过 Memory
outbox" — this is the one place a domain event becomes that outbox row's payload.
"""

from __future__ import annotations

import dataclasses
import json
import uuid
from datetime import datetime
from enum import Enum
from typing import Any

from memoryknowledge.application.records import OutboxRecord
from memoryknowledge.domain.ids import CausationId, CorrelationId


def _json_default(value: Any) -> Any:
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.name
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return dataclasses.asdict(value)
    raise TypeError(f"object of type {type(value)!r} is not JSON serializable")


def encode_event_payload(event: object) -> str:
    """Every real memoryknowledge.domain.events.* payload is a dataclass, so
    dataclasses.asdict() is the common path; a plain dict/primitive is also accepted
    (used by tests seeding an outbox row directly) rather than raising, since the
    resulting JSON shape is identical either way.
    """
    payload = dataclasses.asdict(event) if dataclasses.is_dataclass(event) and not isinstance(event, type) else event
    return json.dumps(payload, default=_json_default, sort_keys=True)


def build_outbox_record(
    event: object,
    event_type: str,
    *,
    aggregate_id: str,
    occurred_at: datetime,
    schema_version: int = 1,
    correlation_id: CorrelationId | None = None,
    causation_id: CausationId | None = None,
    ticket_id: str | None = None,
) -> OutboxRecord:
    return OutboxRecord(
        outbox_id=uuid.uuid4(), event_type=event_type, schema_version=schema_version, aggregate_id=aggregate_id,
        payload=encode_event_payload(event), occurred_at=occurred_at,
        correlation_id=correlation_id or CorrelationId.new_id(), causation_id=causation_id or CausationId.new_id(),
        ticket_id=ticket_id,
    )
