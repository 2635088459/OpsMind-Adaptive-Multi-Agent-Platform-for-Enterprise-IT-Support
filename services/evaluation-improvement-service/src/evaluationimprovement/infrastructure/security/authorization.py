"""13-package-and-class-design `infrastructure/security/authorization.py`. 11-security
§"身份与权限": EVALUATION_VIEWER (read reports/non-sensitive scores),
EVALUATION_AUTHOR (create dataset/candidate drafts), EVALUATION_REVIEWER
(review/publish dataset), EVALUATION_ADMIN (run benchmark, configure gate, initiate
candidate approval), RELEASE_APPROVER (validated by 06, approves Canary). Real
integration with 01-user-access-authentication's own role/claims model is a future
cross-domain-contracts spec; this adapter is a small, explicit, honestly-named static
policy — mirrors memory-knowledge-service's own StaticAuthorizationPolicyAdapter
posture exactly.
"""

from __future__ import annotations

_ACTION_ROLES: dict[str, frozenset[str]] = {
    # SPEC-EI-008: any known role may read — EVALUATION_VIEWER is the read-only floor
    # 11-security itself names ("读取 report 和非敏感 score"), and every higher role can
    # obviously do at least as much.
    "view_evaluation_data": frozenset(
        {"EVALUATION_VIEWER", "EVALUATION_AUTHOR", "EVALUATION_REVIEWER", "EVALUATION_ADMIN", "RELEASE_APPROVER"}
    ),
    "create_dataset": frozenset({"EVALUATION_AUTHOR", "EVALUATION_ADMIN"}),
    "publish_dataset": frozenset({"EVALUATION_REVIEWER", "EVALUATION_ADMIN"}),
    "create_run": frozenset({"EVALUATION_ADMIN"}),
    "cancel_run": frozenset({"EVALUATION_ADMIN"}),
    "create_candidate": frozenset({"EVALUATION_AUTHOR", "EVALUATION_ADMIN"}),
    "approve_candidate": frozenset({"RELEASE_APPROVER", "EVALUATION_ADMIN"}),
    "manage_canary": frozenset({"EVALUATION_ADMIN", "RELEASE_APPROVER"}),
    "manage_gate_policy": frozenset({"EVALUATION_ADMIN"}),
    # SPEC-EI-028: the ingestion side of online sampling is a system/service call (a
    # future SPEC-EI-030 event consumer, not a human author), the same admin-only
    # posture create_run already uses for its own system-triggered writes.
    "collect_online_sample": frozenset({"EVALUATION_ADMIN"}),
}

_SENSITIVE_EVIDENCE_ROLES = frozenset({"EVALUATION_REVIEWER", "EVALUATION_ADMIN", "RELEASE_APPROVER"})


class StaticAuthorizationPolicyAdapter:
    def is_authorized(self, actor_role: str, action: str) -> bool:
        allowed_roles = _ACTION_ROLES.get(action)
        if allowed_roles is None:
            # An action with no explicit policy entry defaults to admin-only — a
            # missing entry must never silently become "everyone allowed."
            return actor_role == "EVALUATION_ADMIN"
        return actor_role in allowed_roles

    def can_view_sensitive_evidence(self, actor_role: str) -> bool:
        """11-security §"数据保护": "Report 默认展示聚合分数；case-level evidence 需要更高
        权限."
        """
        return actor_role in _SENSITIVE_EVIDENCE_ROLES
