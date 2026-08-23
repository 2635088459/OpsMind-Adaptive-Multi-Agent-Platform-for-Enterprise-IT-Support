"""13-package-and-class-design §"adapters/policy/policy_client.py". Real risk
scoring and rule ownership stay with 06-policy-approval-governance — this
deterministic placeholder applies simple, honestly-documented rules and is
never claimed to be more than that. SPEC-TG-007 07 widened it beyond
SPEC-TG-001's own risk_level/requires_approval-only rule with a hard-deny
category (10-failure-handling §"Policy / Approval Failure"): a capability
whose name contains one of a small "never allowed" keyword set is denied
outright, independent of risk-based approval routing — everything else keeps
SPEC-TG-001's own mutating-keyword-implies-approval rule.
"""

from __future__ import annotations

from tool_gateway.domain.enums import RiskLevel
from tool_gateway.domain.values import RiskDecisionRef

_MUTATING_KEYWORDS = ("restart", "create", "delete", "update", "terminate", "revoke")
# SPEC-TG-007: a hard policy rule — never allowed regardless of approval.
# Distinct from _MUTATING_KEYWORDS: those still route to WAITING_APPROVAL and
# can proceed once a human grants it; these are refused outright.
_DENIED_KEYWORDS = ("wipecluster", "dropdatabase", "purgeall")


class StaticPolicyAdapter:
    def __init__(self, clock) -> None:  # noqa: ANN001 - ports.storage_port.ClockPort, kept untyped to avoid a ports->ports import cycle
        self._clock = clock

    def evaluate(self, capability_name: str, requested_by_type: str, input_payload: dict) -> RiskDecisionRef:
        lowered = capability_name.lower()
        now = self._clock.now()

        if any(keyword in lowered for keyword in _DENIED_KEYWORDS):
            return RiskDecisionRef(
                decision_id=f"static-policy:{capability_name}", risk_level=RiskLevel.CRITICAL, requires_approval=False,
                decided_at=now, decided_by="static-policy-adapter", denied=True,
                denial_reason=f"capability '{capability_name}' matches a hard policy deny rule",
            )

        is_high_risk = any(keyword in lowered for keyword in _MUTATING_KEYWORDS)
        return RiskDecisionRef(
            decision_id=f"static-policy:{capability_name}", risk_level=RiskLevel.HIGH if is_high_risk else RiskLevel.LOW,
            requires_approval=is_high_risk, decided_at=now, decided_by="static-policy-adapter",
        )
