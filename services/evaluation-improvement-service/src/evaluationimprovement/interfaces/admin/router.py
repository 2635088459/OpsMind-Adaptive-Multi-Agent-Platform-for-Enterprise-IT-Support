"""05-api-contracts §"管理 API": `GET /evaluation/audit`, `GET/PUT
/evaluation/gates/{gatePolicy}`, `GET /evaluation/graders`. SPEC-EI-035 adds `GET
/evaluation/poison-events` and `POST /evaluation/outbox/dispatch` — the admin
repair/replay surface 10-failure-handling names.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from evaluationimprovement.application.ports_in import (
    AdminRecoveryUseCase,
    AuditQueryUseCase,
    GatePolicyUseCase,
    GraderCatalogUseCase,
    PoisonEventQueryUseCase,
)
from evaluationimprovement.container import (
    get_admin_recovery_port,
    get_audit_query_port,
    get_gate_policy_port,
    get_grader_catalog_port,
    get_poison_event_query_port,
)
from evaluationimprovement.interfaces.admin.mapper import (
    to_audit_response,
    to_dispatch_report_response,
    to_gate_policy_config,
    to_gate_policy_response,
    to_grader_response,
    to_poison_event_response,
)
from evaluationimprovement.interfaces.admin.schemas import (
    AuditRecordResponse,
    DispatchOutboxEventsRequest,
    DispatchReportResponse,
    GatePolicyResponse,
    GraderResponse,
    PoisonEventResponse,
    UpsertGatePolicyRequest,
)
from evaluationimprovement.interfaces.security import require_role as _require_role

router = APIRouter(prefix="/evaluation", tags=["evaluation-admin"])


@router.get("/audit", response_model=list[AuditRecordResponse])
def list_audit_events(
    limit: int = Query(default=100, ge=1, le=1000), _actor: str = Depends(_require_role("manage_gate_policy")),
    port: AuditQueryUseCase = Depends(get_audit_query_port),
) -> list[AuditRecordResponse]:
    return [to_audit_response(e) for e in port.list_audit_events(limit)]


@router.get("/gates/{gate_policy}", response_model=GatePolicyResponse)
def find_gate_policy(gate_policy: str, port: GatePolicyUseCase = Depends(get_gate_policy_port)) -> GatePolicyResponse:
    return to_gate_policy_response(port.find_gate_policy(gate_policy))


@router.put("/gates/{gate_policy}", response_model=GatePolicyResponse)
def upsert_gate_policy(
    gate_policy: str, request: UpsertGatePolicyRequest, actor: str = Depends(_require_role("manage_gate_policy")),
    port: GatePolicyUseCase = Depends(get_gate_policy_port),
) -> GatePolicyResponse:
    return to_gate_policy_response(port.upsert_gate_policy(to_gate_policy_config(gate_policy, request), actor))


@router.get("/graders", response_model=list[GraderResponse])
def list_graders(port: GraderCatalogUseCase = Depends(get_grader_catalog_port)) -> list[GraderResponse]:
    return [to_grader_response(g) for g in port.list_graders()]


@router.get("/poison-events", response_model=list[PoisonEventResponse])
def list_poison_events(
    limit: int = Query(default=100, ge=1, le=1000), _actor: str = Depends(_require_role("manage_gate_policy")),
    port: PoisonEventQueryUseCase = Depends(get_poison_event_query_port),
) -> list[PoisonEventResponse]:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
    §"Poison Event" step 4: "支持 admin replay" — an operator fixes the upstream issue
    seen here, then re-POSTs the same event to the same `/internal/evaluation/v1/
    events/*` ingestion endpoint (never marked processed, so it is applied again).
    """
    return [to_poison_event_response(e) for e in port.list_poison_events(limit)]


@router.post("/outbox/dispatch", response_model=DispatchReportResponse)
def dispatch_outbox_events(
    request: DispatchOutboxEventsRequest, actor: str = Depends(_require_role("manage_gate_policy")),
    port: AdminRecoveryUseCase = Depends(get_admin_recovery_port),
) -> DispatchReportResponse:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling:
    the admin-triggered manual replay for due/backed-off outbox events — audited
    (unlike a background worker's own unattended dispatch loop would be), the same
    "admin repair/replay API 有审计" requirement `/poison-events` visibility satisfies
    for the poison-event side.
    """
    return to_dispatch_report_response(port.dispatch_outbox_events(request.batch_size, actor, request.correlation_id))
