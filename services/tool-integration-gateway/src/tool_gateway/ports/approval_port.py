"""03-state-machine §"Approval Linkage State Machine": "Gateway stores only
approval linkage and decision snapshots. Approval rules, approvers, approval SLA,
and approval history are owned by 06-policy-approval-governance." 04-use-cases
UC-TG-003 step 2: "Gateway calls domain 06 to create an approval request or
publishes an approval requested event."

SPEC-TG-008 wired the ``tool.approval.required.v1`` publish side
(``application.outbox_events.build_approval_required_event``, called from
``application.evaluate_tool_request``) and SPEC-TG-009 wired the decision
consumption side in full (``application.approve_tool_request.
ApproveToolRequestService.consume_approval_decision``: event-id dedup,
idempotent-skip on an already-resolved request, and approval-linkage matching)
— but ``request_approval`` below still only records a local linkage row; no
domain-06 service exists yet in this monorepo to actually call over REST, and
consumption still arrives via ``api.event_routes``'s manual/ops-trigger HTTP
seam rather than a live broker subscription (see
``adapters.events.rabbitmq_consumer`` module docstring). This port is the seam
a real domain-06 REST client would replace.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.domain.values import ApprovalRequestRef, RiskDecisionRef


class ApprovalPort(Protocol):
    def request_approval(self, tool_request_id: object, risk_decision: RiskDecisionRef) -> ApprovalRequestRef:
        """Records an approval request and returns its linkage reference in
        APPROVAL_REQUESTED status. Does not block for a decision — the decision
        arrives later via ``api.event_routes`` consuming
        ``approval.granted.v1``/``approval.denied.v1``.
        """
        ...
