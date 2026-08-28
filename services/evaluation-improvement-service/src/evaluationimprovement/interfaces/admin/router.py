"""05-api-contracts §"管理 API": `GET /evaluation/audit`, `GET/PUT
/evaluation/gates/{gatePolicy}`, `GET /evaluation/graders`.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from evaluationimprovement.application.ports_in import AuditQueryUseCase, GatePolicyUseCase, GraderCatalogUseCase
from evaluationimprovement.container import get_audit_query_port, get_gate_policy_port, get_grader_catalog_port
from evaluationimprovement.interfaces.admin.mapper import (
    to_audit_response,
    to_gate_policy_config,
    to_gate_policy_response,
    to_grader_response,
)
from evaluationimprovement.interfaces.admin.schemas import AuditRecordResponse, GatePolicyResponse, GraderResponse, UpsertGatePolicyRequest
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
