"""SPEC-ARO-040 (phase-10 Conversational Intake): the real outbound HTTP client to
06-policy-approval-governance's own POST /api/v1/approval-requests. Mirrors
evaluation-improvement-service's own HttpPolicyApprovalAdapter (SPEC-EI-026) closely —
same real request shape, same `requestKey`/`requestHash` idempotency-by-convergence
pattern (a resubmitted request_approval() call with the same key converges onto the
same approval request via 06's own idempotency handling, not this adapter's) — but
authenticates via a real OutboundServiceTokenProviderPort (SPEC-ARO-043) rather than
evaluation-improvement's own honest placeholder token, since this service actually has
one.
"""

from __future__ import annotations

import hashlib

import httpx

from agentruntime.application.exceptions import GovernanceApprovalRequestFailedException
from agentruntime.application.ports_out import OutboundServiceTokenProviderPort
from agentruntime.application.records import ApprovalRequestRef
from agentruntime.domain.ids import AgentTaskId, TicketId, WorkflowInstanceId

_APPROVAL_TYPE = "TOOL_EXECUTION"
_SOURCE_DOMAIN = "agent-runtime"


class HttpGovernanceApprovalClient:
    def __init__(self, base_url: str, http_client: httpx.Client | None = None, *, token_provider: OutboundServiceTokenProviderPort | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._token_provider = token_provider
        self._http_client = http_client or httpx.Client(timeout=10.0)

    def request_approval(
        self, agent_task_id: AgentTaskId, workflow_instance_id: WorkflowInstanceId, ticket_id: TicketId, risk_level: str,
        reason: str,
    ) -> ApprovalRequestRef:
        service_token = self._token_provider.get_token() if self._token_provider else None
        if service_token is None:
            raise GovernanceApprovalRequestFailedException("no outbound service token provider is configured")

        request_key = f"{_SOURCE_DOMAIN}:action:{agent_task_id}"
        request_hash = hashlib.sha256(f"{agent_task_id}:{risk_level}:{reason}".encode()).hexdigest()
        payload = {
            "requestKey": request_key, "requestHash": request_hash, "sourceDomain": _SOURCE_DOMAIN,
            "sourceRequestId": str(agent_task_id), "ticketId": str(ticket_id), "workflowInstanceId": str(workflow_instance_id),
            "toolRequestId": None, "executorId": None, "policyDecisionId": None, "approvalType": _APPROVAL_TYPE,
            "riskLevel": risk_level, "constraints": [], "expiresAt": None,
        }

        try:
            response = self._http_client.post(
                f"{self._base_url}/api/v1/approval-requests", json=payload,
                headers={"Authorization": f"Bearer {service_token}", "Content-Type": "application/json"},
            )
        except httpx.HTTPError as exc:
            raise GovernanceApprovalRequestFailedException(f"request to policy-approval-governance failed: {exc}") from exc

        if response.status_code != httpx.codes.CREATED:
            raise GovernanceApprovalRequestFailedException(f"policy-approval-governance returned status {response.status_code}")

        try:
            body = response.json()
            return ApprovalRequestRef(approval_request_id=str(body["approvalRequestId"]), status=str(body["status"]))
        except (ValueError, KeyError) as exc:
            raise GovernanceApprovalRequestFailedException(f"policy-approval-governance response was malformed: {exc}") from exc
