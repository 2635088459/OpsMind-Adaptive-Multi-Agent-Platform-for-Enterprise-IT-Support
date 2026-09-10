"""Parse the shared `opsmind.events` envelope into a flat, defensive value
object. Both the governance envelope (`OutboxDispatchService.buildPayload`) and
the eval-improvement envelope have the same outer shape; the only field that
varies in type is `payload` (dict, or a JSON string for some producers).
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from typing import Any

_NS = uuid.NAMESPACE_URL


@dataclass(frozen=True)
class RelayEnvelope:
    event_id: str
    event_type: str
    producer: str
    schema_version: int
    correlation_id: str          # raw — NOT guaranteed to be a UUID
    causation_id: str | None
    ticket_id: str | None
    occurred_at: str             # ISO-8601 string, passed through verbatim
    payload: dict[str, Any] = field(default_factory=dict)
    raw: dict[str, Any] = field(default_factory=dict)


class EnvelopeParseError(ValueError):
    """The message body is not a usable `opsmind.events` envelope."""


def parse_envelope(body: bytes | str, routing_key: str | None = None) -> RelayEnvelope:
    try:
        raw = json.loads(body)
    except (json.JSONDecodeError, TypeError) as exc:
        raise EnvelopeParseError(f"body is not JSON: {exc}") from exc
    if not isinstance(raw, dict):
        raise EnvelopeParseError("body is not a JSON object")

    event_id = _clean(raw.get("eventId")) or _clean(raw.get("event_id"))
    if not event_id:
        raise EnvelopeParseError("envelope has no eventId")

    # The AMQP routing key the binding matched is authoritative: it is always the
    # fully-versioned canonical event type (`ticket.resolved.v1`), whereas the
    # envelope's own `eventType` field varies by producer — ticket-workflow serializes
    # the *unversioned* form (`ticket.resolved`) and carries the version separately,
    # while governance / agent-runtime / eval already embed `.vN` in `eventType` and
    # ship no routing key in the body at all.
    event_type = (
        _clean(routing_key)
        or _clean(raw.get("routingKey"))
        or _clean(raw.get("eventType"))
        or _clean(raw.get("event_type"))
    )
    if not event_type:
        raise EnvelopeParseError("envelope has no routingKey/eventType")

    return RelayEnvelope(
        event_id=event_id,
        event_type=event_type,
        producer=_clean(raw.get("producer")) or "unknown",
        schema_version=_int_or(raw.get("schemaVersion") or raw.get("schema_version"), 1),
        correlation_id=_clean(raw.get("correlationId")) or _clean(raw.get("correlation_id")) or event_id,
        causation_id=_clean(raw.get("causationId")) or _clean(raw.get("causation_id")),
        ticket_id=_clean(raw.get("ticketId")) or _clean(raw.get("ticket_id")),
        occurred_at=_clean(raw.get("occurredAt")) or _clean(raw.get("occurred_at")) or "",
        payload=_payload(raw.get("payload")),
        raw=raw,
    )


def _payload(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return decoded if isinstance(decoded, dict) else {}
    return {}


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _int_or(value: Any, default: int) -> int:
    try:
        parsed = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default
    return parsed if parsed >= 1 else default


def coerce_uuid(value: Any, seed: str) -> str | None:
    """Return a canonical UUID string. If `value` already is a UUID, echo it;
    otherwise derive a stable one from it so the strict `UUID`-typed
    agent-runtime fields (`correlation_id`, `causation_id`, `ticket_id`) always
    validate without losing the correlation to the original value.
    """
    text = _clean(value)
    if text is None:
        return None
    try:
        return str(uuid.UUID(text))
    except ValueError:
        return str(uuid.uuid5(_NS, f"opsmind:{seed}:{text}"))


def require_uuid(value: Any, seed: str) -> str:
    return coerce_uuid(value, seed) or str(uuid.uuid5(_NS, f"opsmind:{seed}:missing"))


def uuid_exact(value: Any) -> str | None:
    """Canonical UUID string only if `value` genuinely parses as one, else None
    — used for `workflow_instance_id` / `tool_request_id`, where a synthesized id
    would just make the target 404/422; skipping that delivery is correct.
    """
    text = _clean(value)
    if text is None:
        return None
    try:
        return str(uuid.UUID(text))
    except ValueError:
        return None
