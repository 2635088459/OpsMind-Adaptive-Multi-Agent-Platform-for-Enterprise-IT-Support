"""Not in 13-package-and-class-design's literal tree — added the same way
`infrastructure/runtime/agent_runtime_client.py` was, as the concrete adapter for
application.ports_out.PolicyApprovalPort. SPEC-EI-026 (policy-approval-release-
contract) adds HttpPolicyApprovalAdapter, the real httpx client against
06-policy-approval-governance's own `POST /api/v1/approval-requests` contract (see
policy-approval-governance-service's own ApprovalController.java /
RequestApprovalRequest.java / ApprovalRequestResponse.java); container.py picks
between it and FakePolicyApprovalAdapter via Settings.policy_approval_mode, the same
seam agent_runtime_client.py already established for SPEC-EI-012. domain-rules
"forbidden: policy_approval_ownership_bypass" — both adapters only ever *request* an
approval reference, never grant one; every real request lands as 06's own REQUESTED
status, and the fake's every request comes back PENDING.
"""

from __future__ import annotations

import hashlib
import uuid

import httpx

from evaluationimprovement.application.exceptions import PolicyApprovalUnavailableException
from evaluationimprovement.application.records import ApprovalRequestRef
from evaluationimprovement.domain.ids import CandidateId


class FakePolicyApprovalAdapter:
    def request_approval(self, candidate_id: CandidateId, target_component: str, risk_level: str, requested_by: str) -> ApprovalRequestRef:  # noqa: ARG002
        return ApprovalRequestRef(approval_request_id=f"approval-{uuid.uuid4()}", status="PENDING")


class HttpPolicyApprovalAdapter:
    """SPEC-EI-026 (policy-approval-release-contract): the real client for
    06-policy-approval-governance's own `POST /api/v1/approval-requests`.
    `approvalType=GENERIC` (06's own catch-all — this is a candidate release
    approval, not a tool/ticket/workflow action any of 06's other named types cover).
    `requestKey` is `evaluation-improvement:candidate:{candidateId}` — a resubmitted
    request_approval() call for the same candidate always carries the same key, which
    is what lets 06's own idempotency handling (not this adapter) converge it onto
    the same approval request rather than opening a duplicate.

    06's own GovernanceRequestContext requires a real Spring Security Authentication
    with a non-blank name to populate `requestedBy` server-side — no cross-service
    identity mechanism issues one anywhere in this repo yet (the same gap
    HttpAgentRuntimeEvaluationAdapter's own docstring names for 03's endpoint). This
    adapter still sends `Settings.policy_approval_service_token` as a bearer token
    when configured — carrying that half of the contract now, even though nothing
    upstream issues a real token yet, is this spec's own honest placeholder for it.
    """

    def __init__(self, client: httpx.Client, base_url: str, service_token: str | None = None) -> None:
        self._client = client
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token

    def request_approval(self, candidate_id: CandidateId, target_component: str, risk_level: str, requested_by: str) -> ApprovalRequestRef:  # noqa: ARG002
        request_key = f"evaluation-improvement:candidate:{candidate_id}"
        request_hash = hashlib.sha256(f"{candidate_id}:{target_component}:{risk_level}".encode()).hexdigest()
        payload = {
            "requestKey": request_key,
            "requestHash": request_hash,
            "sourceDomain": "evaluation-improvement",
            "sourceRequestId": str(candidate_id),
            "ticketId": None,
            "workflowInstanceId": None,
            "toolRequestId": None,
            "executorId": None,
            "policyDecisionId": None,
            "approvalType": "GENERIC",
            "riskLevel": risk_level,
            "constraints": [],
            "expiresAt": None,
        }
        headers = {"Authorization": f"Bearer {self._service_token}"} if self._service_token else {}
        try:
            response = self._client.post(f"{self._base_url}/api/v1/approval-requests", json=payload, headers=headers)
            response.raise_for_status()
            body = response.json()
            return ApprovalRequestRef(approval_request_id=str(body["approvalRequestId"]), status=str(body["status"]))
        except httpx.TimeoutException as exc:
            raise PolicyApprovalUnavailableException(candidate_id, "request timed out") from exc
        except httpx.HTTPStatusError as exc:
            raise PolicyApprovalUnavailableException(candidate_id, f"returned {exc.response.status_code}") from exc
        except httpx.HTTPError as exc:
            raise PolicyApprovalUnavailableException(candidate_id, str(exc)) from exc
        except (KeyError, TypeError, ValueError) as exc:
            raise PolicyApprovalUnavailableException(candidate_id, f"malformed response: {exc}") from exc
