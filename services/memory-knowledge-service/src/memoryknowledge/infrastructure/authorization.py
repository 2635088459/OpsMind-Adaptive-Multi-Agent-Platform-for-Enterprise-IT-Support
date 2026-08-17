"""02-business-invariants §"安全不变量": "高敏 classification 的 memory 默认不可跨
queue / role 检索"; "所有 admin override 必须审计." A real policy-engine integration
(06-policy-approval-governance) is out of this spec's scope; this adapter is a small,
explicit, honestly-named static policy — real RBAC/ABAC is phase-07
(security-and-governance).
"""

from __future__ import annotations

from memoryknowledge.domain.ids import MemoryCandidateId, MemoryId
from memoryknowledge.domain.values import AccessScope

_PUBLIC_CLASSIFICATIONS = frozenset({"PUBLIC", "INTERNAL"})
_RESTRICTED_ROLES = frozenset({"admin", "security_reviewer", "knowledge_owner"})


class StaticAuthorizationPolicyAdapter:
    def is_retrieval_authorized(self, access_scope: AccessScope, classification: str) -> bool:
        if classification.upper() in _PUBLIC_CLASSIFICATIONS:
            return True
        return access_scope.role in _RESTRICTED_ROLES

    def is_deletion_authorized(self, actor_id: str, memory_id: MemoryId) -> bool:
        return bool(actor_id and actor_id.strip())

    def is_conflict_resolution_authorized(self, actor_id: str, candidate_id: MemoryCandidateId) -> bool:
        """Same honest-simple posture as is_deletion_authorized: a non-blank actor is
        the whole check today, real RBAC/ABAC is phase-07 (security-and-governance).
        """
        return bool(actor_id and actor_id.strip())

    def is_organizational_relation_authorized(self, access_scope: AccessScope) -> bool:
        """11-security §"Graph Security": "OWNED_BY / POLICY_RULE 等组织关系默认只对
        authorized role 返回" — reuses the same _RESTRICTED_ROLES set
        is_retrieval_authorized already applies to non-public classifications; a
        real capability system (e.g. a distinct "knowledge_base_read"-style grant)
        stays phase-07's own further-out scope, same honest-simple posture as every
        other method on this adapter.
        """
        return access_scope.role in _RESTRICTED_ROLES
