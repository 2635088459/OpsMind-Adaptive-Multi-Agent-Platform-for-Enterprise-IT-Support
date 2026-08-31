"""Shared HTTP-boundary RBAC gate for both interfaces.rest.router and
interfaces.admin.router. 05-api-contracts §"API 原则": "所有写 API 必须要求 01 提供的 service
identity 或 evaluator/admin role." Real JWT/claims integration with
01-user-access-authentication is a future cross-domain-contracts spec; `require_role()`
reads a caller-asserted `X-Actor-Id`/`X-Actor-Role` header pair as this spec's own
honest placeholder for that trusted identity, mirroring the "trusted caller asserts"
precedent several sibling domains' own phase-00 specs used before their own JWT
integration landed. Depends only on evaluationimprovement.container's own port
accessor (get_authorization_port) — never touches infrastructure directly.

SPEC-EI-008 / 11-security: "07 依赖 01 提供 actor、service identity、tenant scope 和 role
claims" — `tenant_id()` reads a caller-asserted `X-Tenant-Id` header the same way,
defaulting to `"default"` so a caller (or test) that never sends it still gets a
single, consistent tenant rather than an error.
"""

from __future__ import annotations

from fastapi import Depends, Header, HTTPException, status

from evaluationimprovement.container import get_authorization_port


def actor(x_actor_id: str = Header(..., alias="X-Actor-Id"), x_actor_role: str = Header(..., alias="X-Actor-Role")) -> tuple[str, str]:
    return x_actor_id, x_actor_role


def optional_actor(
    x_actor_id: str | None = Header(default=None, alias="X-Actor-Id"), x_actor_role: str | None = Header(default=None, alias="X-Actor-Role"),
) -> tuple[str, str]:
    """SPEC-EI-034 (evaluation-security-redaction-observability): unlike `actor()`,
    a read endpoint must keep working for a caller who asserts no identity at all —
    05-api-contracts's own default-read-floor (EVALUATION_VIEWER, "读取 report 和非敏感
    score") applies, not a 400. Only case-level *evidence* visibility
    (`can_view_sensitive_evidence()`) actually depends on the role this resolves to;
    every other read stays unaffected by an absent header pair.
    """
    return x_actor_id or "anonymous", x_actor_role or "EVALUATION_VIEWER"


def tenant_id(x_tenant_id: str = Header(default="default", alias="X-Tenant-Id")) -> str:
    return x_tenant_id


def require_role(action: str):  # noqa: ANN201
    def _dependency(caller: tuple[str, str] = Depends(actor)) -> str:
        actor_id, actor_role = caller
        if not get_authorization_port().is_authorized(actor_role, action):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=f"role {actor_role!r} is not authorized to {action!r}")
        return actor_id
    return _dependency
