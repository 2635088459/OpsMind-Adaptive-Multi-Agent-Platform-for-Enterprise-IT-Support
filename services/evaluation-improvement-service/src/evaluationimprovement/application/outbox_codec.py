"""Encodes a evaluationimprovement.domain.events.* dataclass into a full
06-event-contracts §"Event Envelope" JSON payload, wrapped in an
evaluationimprovement.application.records.OutboxRecord. Pure stdlib — lives in
application, not infrastructure, because every application service that publishes an
event needs it and application must not depend on infrastructure (the import-linter
"forbidden" contract).
"""

from __future__ import annotations

import dataclasses
import json
import uuid
from datetime import datetime
from enum import Enum
from typing import Any

from evaluationimprovement.application.records import OutboxRecord
from evaluationimprovement.domain.ids import CorrelationId

_PRODUCER = "evaluation-improvement-service"


def to_correlation_id(value: str) -> CorrelationId:
    """Callers carry `correlationId` as a plain string end-to-end (API/event contract
    shape); CorrelationId itself wraps a real uuid.UUID. A caller-supplied value that
    is not already a valid UUID is deterministically mapped into the UUID space
    (uuid5) rather than rejected, since 05-api-contracts never requires the caller's
    own correlation id to already be UUID-shaped.
    """
    try:
        return CorrelationId(uuid.UUID(value))
    except ValueError:
        return CorrelationId(uuid.uuid5(uuid.NAMESPACE_URL, value))


def _to_serializable(value: Any) -> Any:
    """Mirrors memory-knowledge-service's own outbox_codec._to_serializable: every
    domain.ids.* value object already defines __str__ returning its plain string
    form, so stringifying any dataclass encountered while walking an event's fields
    is correct for every event this service publishes.
    """
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return str(value)
    if isinstance(value, (list, tuple)):
        return [_to_serializable(item) for item in value]
    if isinstance(value, dict):
        return {key: _to_serializable(item) for key, item in value.items()}
    return value


def _json_default(value: Any) -> Any:
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.name
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return str(value)
    raise TypeError(f"object of type {type(value)!r} is not JSON serializable")


def _event_payload(event: object) -> dict[str, Any]:
    if dataclasses.is_dataclass(event) and not isinstance(event, type):
        return {f.name: _to_serializable(getattr(event, f.name)) for f in dataclasses.fields(event)}
    return _to_serializable(event)


def build_outbox_record(
    event: object,
    event_type: str,
    *,
    aggregate_id: str,
    occurred_at: datetime,
    correlation_id: CorrelationId,
    schema_version: int = 1,
) -> OutboxRecord:
    """06-event-contracts §"Event Envelope 要求": every event must carry `eventId`,
    `eventType`, `eventVersion`, `occurredAt`, `producer`, `traceId`, `correlationId`,
    `runId`, `candidateId`, `payload`. `traceId` mirrors `correlationId` here — this
    spec's scope introduces no distinct distributed-trace-context concept beyond it;
    real OpenTelemetry trace-context propagation is 12-observability's own separate
    concern. `runId`/`candidateId` are read off the event dataclass's own matching
    field when present, `None` otherwise (an event with neither, e.g. none exist in
    this spec's own published-event list, would simply carry both as `None`).
    """
    outbox_id = uuid.uuid4()
    run_id = getattr(event, "run_id", None)
    candidate_id = getattr(event, "candidate_id", None)
    envelope = {
        "eventId": str(outbox_id),
        "eventType": event_type,
        "eventVersion": schema_version,
        "occurredAt": occurred_at.isoformat(),
        "producer": _PRODUCER,
        "traceId": str(correlation_id),
        "correlationId": str(correlation_id),
        "runId": str(run_id) if run_id is not None else None,
        "candidateId": str(candidate_id) if candidate_id is not None else None,
        "payload": _event_payload(event),
    }
    return OutboxRecord(
        outbox_id=outbox_id, event_type=event_type, schema_version=schema_version, aggregate_id=aggregate_id,
        payload=json.dumps(envelope, default=_json_default, sort_keys=True), occurred_at=occurred_at,
        correlation_id=correlation_id,
    )
