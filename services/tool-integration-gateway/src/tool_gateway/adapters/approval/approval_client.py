"""13-package-and-class-design's own ``adapters/`` tree lists ``policy/`` but no
matching ``approval/`` directory for ``ports.approval_port.ApprovalPort`` — added
here, mirroring ``adapters/policy/policy_client.py``'s own 1:1 port-to-adapter
shape, the same way memory-knowledge-service's SPEC-MK-001 extended its own
LLD-listed adapter set where the literal tree left a named port unimplemented.

Real integration with 06-policy-approval-governance (a REST call or consuming
``approval.requested.v1``) is phase-02/06 scope — see
``ports.approval_port``'s own module docstring. This in-memory adapter only
records the linkage reference in APPROVAL_REQUESTED status; the decision itself
arrives through ``application.approve_tool_request`` today.
"""

from __future__ import annotations

from tool_gateway.domain.enums import ApprovalLinkageStatus
from tool_gateway.domain.ids import ApprovalRequestId
from tool_gateway.domain.values import ApprovalRequestRef, RiskDecisionRef


class InMemoryApprovalAdapter:
    def __init__(self, clock) -> None:  # noqa: ANN001 - ports.storage_port.ClockPort
        self._clock = clock

    def request_approval(self, tool_request_id: object, risk_decision: RiskDecisionRef) -> ApprovalRequestRef:
        return ApprovalRequestRef(
            approval_request_id=ApprovalRequestId.new_id(), status=ApprovalLinkageStatus.APPROVAL_REQUESTED,
            requested_at=self._clock.now(),
        )
