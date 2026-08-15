"""SPEC-ARO-032 module boundary placeholder for CapabilityPolicyPort, mirroring
StaticWorkflowDefinitionCatalogAdapter's own honesty-over-fabrication stance: rather
than inventing a full policy-authoring/versioning system with no 07-data-model table
behind it, this adapter resolves against one hardcoded agent_role -> allowed-capability
map, covering the two agent_role values already exercised elsewhere in this codebase
(SPEC-ARO-009's own "triage_agent"/"kb_agent" fixtures). A later spec is expected to
replace this with a real authored policy store once Planner starts assigning agent_role
for real (SPEC-ARO-007's own still-pending deferral — until then, almost no live Tool
Request carries a role to check in the first place; see RequestToolService's own
docstring for why this policy is a no-op when agent_role is absent).
"""

from __future__ import annotations

_DEFAULT_POLICY: dict[str, frozenset[str]] = {
    "triage_agent": frozenset({"service_operations", "diagnostics"}),
    "kb_agent": frozenset({"knowledge_base_read"}),
}


class StaticCapabilityPolicyAdapter:
    def is_authorized(self, agent_role: str, capability: str) -> bool:
        return capability in _DEFAULT_POLICY.get(agent_role, frozenset())
