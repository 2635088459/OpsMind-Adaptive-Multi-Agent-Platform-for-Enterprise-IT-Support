"""04-use-cases UC-TG-002/UC-TG-003 §"Gateway computes risk decision." Real risk
scoring and rule ownership stay with 06-policy-approval-governance, which does
not exist as a live service in this monorepo yet — SPEC-TG-007 widened the
deterministic ``StaticPolicyAdapter`` placeholder with a hard-deny verdict
(``RiskDecisionRef.denied``) and wired ``policy.rule.changed.v1`` consumption
(``application.consume_policy_rule_changed``), but a real HTTP/event client
against an actual domain-06 service is still deferred until that service is
built — this port is the seam a future spec replaces, mirroring memory-
knowledge-service's own AuthorizationPort placeholder-vs-real split.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.domain.values import RiskDecisionRef


class PolicyPort(Protocol):
    def evaluate(self, capability_name: str, requested_by_type: str, input_payload: dict) -> RiskDecisionRef:
        """Returns the risk decision for one ToolRequest. ``requires_approval``
        on the returned RiskDecisionRef drives whether ToolRequest.require_approval()
        or ToolRequest.auto_approve() is called next (02-business-invariants
        INV-TG-005).
        """
        ...
