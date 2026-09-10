"""Pure transform: `RelayEnvelope` -> the list of HTTP deliveries it fans out
to. No I/O, no broker, no clock beyond `occurred_at` fallback — everything here
is unit-tested against the real wire contracts in
`docs/specs/cross-cutting/SPEC-XREL-001-*`.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from event_relay.envelope import RelayEnvelope, coerce_uuid, require_uuid, uuid_exact

AGENT_EVENTS_PATH = "/internal/agent-runtime/v1/events"
AGENT_IMPROVEMENT_PATH = "/internal/agent-runtime/v1/events/improvement-promoted"
TG_APPROVAL_GRANTED_PATH = "/internal/tool-gateway/v1/events/approval-granted"
TG_APPROVAL_DENIED_PATH = "/internal/tool-gateway/v1/events/approval-denied"

# memory-knowledge's own event listener. Its request schemas already carry a
# @model_validator that unwraps the raw ticket-workflow / agent-runtime outbox
# envelope (interfaces/event/schemas.py) — so the relay forwards the consumed body
# verbatim, exactly like improvement.promoted.
_MK_EVENTS_BASE = "/internal/memory/v1/events"
_MEMORY_SOURCE_PATH_BY_TYPE = {
    "ticket.resolved.v1": f"{_MK_EVENTS_BASE}/ticket-resolved",
    "ticket.closed.v1": f"{_MK_EVENTS_BASE}/ticket-closed",
    "workflow.completed.v1": f"{_MK_EVENTS_BASE}/workflow-completed",
    "workflow.failed.v1": f"{_MK_EVENTS_BASE}/workflow-failed",
}

# agent-runtime's ConsumeRuntimeEventService routes ONLY this constant; the
# `decision` field inside the payload discriminates granted / denied / expired
# (see consume_approval.py's own "single-event-type-with-status-discriminator"
# design note).
_RUNTIME_APPROVAL_EVENT_TYPE = "approval.granted.v1"

_DECISION_BY_TYPE = {
    "approval.granted.v1": "APPROVED",
    "approval.denied.v1": "DENIED",
    "approval.expired.v1": "EXPIRED",
}


@dataclass(frozen=True)
class Delivery:
    target: str          # "agent-runtime" | "tool-gateway" — key into Settings.base_urls
    path: str
    json_body: dict[str, Any]
    describe: str
    correlation_id: str = ""


def plan_deliveries(env: RelayEnvelope) -> list[Delivery]:
    if env.event_type in _DECISION_BY_TYPE:
        return _plan_approval(env, _DECISION_BY_TYPE[env.event_type])
    if env.event_type == "improvement.promoted.v1":
        return _plan_improvement(env)
    if env.event_type in _MEMORY_SOURCE_PATH_BY_TYPE:
        return _plan_memory_source(env)
    return []


def _plan_approval(env: RelayEnvelope, decision: str) -> list[Delivery]:
    payload = env.payload
    workflow_instance_id = uuid_exact(payload.get("workflowInstanceId"))
    tool_request_id = uuid_exact(payload.get("toolRequestId"))
    approval_request_id = _text(payload.get("approvalRequestId")) or env.event_id
    decided_by = _text(payload.get("decidedBy")) or "policy-approval-governance"
    denial_reason = _text(payload.get("reason"))
    correlation_raw = env.correlation_id

    deliveries: list[Delivery] = []

    # --- agent-runtime: resume / fail the WAITING_FOR_APPROVAL workflow --------
    if workflow_instance_id is not None:
        inner = {
            "approvalRequestId": approval_request_id,
            "decision": decision,
            "approvedBy": decided_by,
        }
        body = {
            "event_id": env.event_id,
            "event_type": _RUNTIME_APPROVAL_EVENT_TYPE,
            "producer": env.producer,
            "schema_version": max(env.schema_version, 1),
            "correlation_id": require_uuid(correlation_raw, "correlation"),
            "causation_id": require_uuid(env.causation_id or env.event_id, "causation"),
            "ticket_id": require_uuid(env.ticket_id, "ticket"),
            "workflow_instance_id": workflow_instance_id,
            "occurred_at": env.occurred_at or _now_iso(),
            "payload": json.dumps(inner),
        }
        deliveries.append(Delivery(
            target="agent-runtime", path=AGENT_EVENTS_PATH, json_body=body,
            describe=f"approval {decision} -> agent-runtime workflow={workflow_instance_id}",
            correlation_id=str(correlation_raw),
        ))

    # --- tool-integration-gateway: resume / fail the WAITING_APPROVAL request --
    # No expired endpoint on the gateway — its own recovery scan covers that side.
    if tool_request_id is not None and decision in ("APPROVED", "DENIED"):
        if decision == "APPROVED":
            body = {
                "event_id": env.event_id,
                "approval_request_id": approval_request_id,
                "tool_request_id": tool_request_id,
                "ticket_id": env.ticket_id,
                "workflow_instance_id": uuid_exact(payload.get("workflowInstanceId")),
                "approved_by": decided_by,
                "constraints": {},
                "correlation_id": str(correlation_raw),
            }
            path = TG_APPROVAL_GRANTED_PATH
        else:
            body = {
                "event_id": env.event_id,
                "approval_request_id": approval_request_id,
                "tool_request_id": tool_request_id,
                "ticket_id": env.ticket_id,
                "workflow_instance_id": uuid_exact(payload.get("workflowInstanceId")),
                "denied_by": decided_by,
                "denial_reason": denial_reason,
                "correlation_id": str(correlation_raw),
            }
            path = TG_APPROVAL_DENIED_PATH
        deliveries.append(Delivery(
            target="tool-gateway", path=path, json_body=body,
            describe=f"approval {decision} -> tool-gateway tool_request={tool_request_id}",
            correlation_id=str(correlation_raw),
        ))

    return deliveries


def _plan_improvement(env: RelayEnvelope) -> list[Delivery]:
    # agent-runtime's ImprovementPromotedEventRequest has a model-validator that
    # unwraps exactly this envelope shape ({eventId,eventType,occurredAt,producer,
    # ...payload}). Forward the raw consumed body unchanged so that validator does
    # the flattening — re-building it here would just risk drifting from it.
    body = dict(env.raw)
    body.setdefault("eventId", env.event_id)
    body.setdefault("eventType", "improvement.promoted.v1")
    body.setdefault("occurredAt", env.occurred_at or _now_iso())
    body.setdefault("producer", env.producer)
    return [Delivery(
        target="agent-runtime", path=AGENT_IMPROVEMENT_PATH, json_body=body,
        describe=f"improvement.promoted -> agent-runtime candidate={env.payload.get('candidate_id', '?')}",
        correlation_id=coerce_uuid(env.correlation_id, "correlation") or "",
    )]


def _plan_memory_source(env: RelayEnvelope) -> list[Delivery]:
    # ticket.resolved/closed.v1 (from ticket-workflow) and workflow.completed/failed.v1
    # (from agent-runtime) feed memory-knowledge's candidate-memory pipeline so the
    # knowledge base grows from real resolutions. memory-knowledge's request schemas
    # already accept the raw outbox envelope (both upstream shapes) via their own
    # @model_validator — forward the consumed body unchanged, same as _plan_improvement.
    path = _MEMORY_SOURCE_PATH_BY_TYPE[env.event_type]
    body = dict(env.raw)
    body.setdefault("eventId", env.event_id)
    body.setdefault("eventType", env.event_type)
    return [Delivery(
        target="memory-knowledge", path=path, json_body=body,
        describe=f"{env.event_type} -> memory-knowledge {path.rsplit('/', 1)[-1]}",
        correlation_id=coerce_uuid(env.correlation_id, "correlation") or "",
    )]


def _text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _now_iso() -> str:
    return datetime.now(UTC).isoformat()
