"""13-package-and-class-design's own ``api/`` tree lists only
``runtime_routes.py``/``connector_admin_routes.py``/``result_routes.py``/
``schemas.py`` — this module is a phase-07 extension, mirroring
``connector_admin_routes.py``'s/``event_routes.py``'s own precedent for
LLD-cited admin surfaces 13-package-and-class-design's own tree never named a
file for:

- SPEC-TG-027 "Audit Query And Admin Reporting" 12-observability
  §"Audit Observability": ``GET /admin/audit`` (recent, optionally filtered
  by exactly one of ``ticket_id``/``actor_id``/``connector_id``).
- SPEC-TG-028 "Outbox Poison Replay Admin Repair" 10-failure-handling
  §"Poison Request": ``GET /admin/outbox/dead-letter`` +
  ``POST /admin/outbox/{outboxId}/replay``.
- SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
  §"Gateway Crash Recovery": ``POST /admin/recovery/run`` — see
  ``application.gateway_recovery`` module docstring for why this is
  admin-triggered rather than run automatically at process boot.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from tool_gateway.api.schemas import AuditRecordResponse, OutboxRecordResponse, RecoverySummaryResponse, ReplayOutboxRequest
from tool_gateway.application.ports_in import AdminOutboxUseCase, AuditQueryUseCase, GatewayRecoveryUseCase
from tool_gateway.application.views import AuditRecordView, OutboxRecordView
from tool_gateway.container import get_admin_outbox_port, get_audit_query_port, get_gateway_recovery_port

router = APIRouter(prefix="/internal/tool-gateway/v1/admin", tags=["admin"])


def _audit_to_response(view: AuditRecordView) -> AuditRecordResponse:
    return AuditRecordResponse(
        audit_id=view.audit_id, action=view.action, resource_type=view.resource_type, resource_id=view.resource_id,
        outcome=view.outcome, actor_id=view.actor_id, correlation_id=view.correlation_id, recorded_at=view.recorded_at,
        ticket_id=view.ticket_id, detail=view.detail, tool_request_id=view.tool_request_id,
        execution_id=view.execution_id, connector_id=view.connector_id,
    )


def _outbox_to_response(view: OutboxRecordView) -> OutboxRecordResponse:
    return OutboxRecordResponse(
        outbox_id=view.outbox_id, aggregate_type=view.aggregate_type, aggregate_id=view.aggregate_id,
        event_type=view.event_type, event_version=view.event_version, payload=view.payload, status=view.status,
        attempts=view.attempts, occurred_at=view.occurred_at, correlation_id=view.correlation_id,
    )


@router.get("/audit", response_model=list[AuditRecordResponse])
def query_audit(
    ticket_id: str | None = Query(default=None),
    actor_id: str | None = Query(default=None),
    connector_id: str | None = Query(default=None),
    limit: int = Query(default=50, le=500),
    port: AuditQueryUseCase = Depends(get_audit_query_port),
) -> list[AuditRecordResponse]:
    """12-observability §"Audit Observability": at most one filter is applied
    per call — the three underlying queries are independent indexes/columns,
    not a combinable WHERE clause (see ``application.query_audit.
    AuditQueryService``'s own docstring for what "by workflow"/"by approval
    request" still cannot answer). No filter given falls back to
    ``find_recent``.
    """

    if ticket_id is not None:
        return [_audit_to_response(v) for v in port.find_by_ticket_id(ticket_id, limit)]
    if actor_id is not None:
        return [_audit_to_response(v) for v in port.find_by_actor_id(actor_id, limit)]
    if connector_id is not None:
        return [_audit_to_response(v) for v in port.find_by_connector_id(connector_id, limit)]
    return [_audit_to_response(v) for v in port.find_recent(limit)]


@router.get("/outbox/dead-letter", response_model=list[OutboxRecordResponse])
def list_dead_letter_outbox(
    limit: int = Query(default=50, le=500), port: AdminOutboxUseCase = Depends(get_admin_outbox_port),
) -> list[OutboxRecordResponse]:
    return [_outbox_to_response(v) for v in port.list_dead_letter(limit)]


@router.post("/outbox/{outbox_id}/replay", response_model=OutboxRecordResponse)
def replay_outbox_event(
    outbox_id: str, request: ReplayOutboxRequest, port: AdminOutboxUseCase = Depends(get_admin_outbox_port),
) -> OutboxRecordResponse:
    """10-failure-handling §"Poison Request": "Poison requests are not
    executed automatically again and require admin audit" — every successful
    replay writes an ``outbox_event_replayed`` audit record (see
    ``application.admin_outbox.AdminOutboxService.replay``'s own docstring);
    a not-found/not-dead-letter attempt raises before reaching that point.
    """

    return _outbox_to_response(port.replay(outbox_id, request.requested_by, request.correlation_id))


@router.post("/recovery/run", response_model=RecoverySummaryResponse)
def run_recovery(
    batch_size: int = Query(default=200, le=1000), port: GatewayRecoveryUseCase = Depends(get_gateway_recovery_port),
) -> RecoverySummaryResponse:
    """10-failure-handling §"Gateway Crash Recovery": an operator/init-
    container/deploy script calls this once at actual process startup (see
    ``application.gateway_recovery`` module docstring for why it is not
    triggered automatically).
    """

    summary = port.run_recovery(batch_size)
    return RecoverySummaryResponse(leases_reclaimed=summary.leases_reclaimed, outbox_events_published=summary.outbox_events_published)
