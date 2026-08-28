"""Not in 13-package-and-class-design's literal tree — added the same way
`infrastructure/runtime/agent_runtime_client.py` was, as the concrete adapter for
application.ports_out.PolicyApprovalPort. Real integration with
06-policy-approval-governance is SPEC-EI-026/SPEC-EI-032 scope. domain-rules
"forbidden: policy_approval_ownership_bypass" — this fake only ever *requests* an
approval reference, it never grants one; every request comes back PENDING.
"""

from __future__ import annotations

import uuid

from evaluationimprovement.application.records import ApprovalRequestRef
from evaluationimprovement.domain.ids import CandidateId


class FakePolicyApprovalAdapter:
    def request_approval(self, candidate_id: CandidateId, target_component: str, risk_level: str, requested_by: str) -> ApprovalRequestRef:  # noqa: ARG002
        return ApprovalRequestRef(approval_request_id=f"approval-{uuid.uuid4()}", status="PENDING")
