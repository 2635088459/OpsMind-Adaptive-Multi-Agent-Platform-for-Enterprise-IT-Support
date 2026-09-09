"""ToolGatewayPort adapters — 13-package-and-class-design §"Adapters": "Tool Gateway
adapter is Runtime's only exit to tool systems."

Two implementations:

* ``LoggingToolGatewayPort`` — the SPEC-ARO-001 placeholder: logs a dispatch and
  acknowledges it as DISPATCHED, nothing else. Still the default (and every hermetic
  test's fixture).
* ``HttpToolGatewayPort`` — phase-05 (tool-gateway-mediation): a real httpx client
  against tool-integration-gateway's own Runtime API
  (``POST /internal/tool-gateway/v1/tool-requests`` then, for a QUEUED low-risk
  request, ``POST .../tool-requests/{id}/execute``). This deployment runs no async
  ``tool.completed.v1`` consumer, so the adapter drives the execution synchronously in
  the dispatch step and hands the terminal outcome back on the acknowledgement;
  DispatchToolRequestsService applies it (waking the WAITING_FOR_TOOL workflow) the
  same way ConsumeToolResultService would for a real event. A non-terminal gateway
  status (still queued, or PENDING_APPROVAL) comes back as DISPATCHED and the workflow
  keeps waiting — the approval-granted path and the stale-tool-wait recovery scan both
  still apply unchanged.
"""

from __future__ import annotations

import json
import logging

import httpx

from agentruntime.application.ports_out import ClockPort
from agentruntime.application.records import ToolDispatchAcknowledgement, ToolRequestRecord
from agentruntime.domain.enums import ToolRequestStatus

logger = logging.getLogger(__name__)

# tool-integration-gateway ToolRequestStatus name -> Runtime ToolRequestStatus.
_TERMINAL_OK = {"COMPLETED"}
_TERMINAL_FAIL = {"TERMINAL_FAILED", "REJECTED", "POLICY_DENIED", "APPROVAL_DENIED", "CANCELLED"}


class LoggingToolGatewayPort:
    def __init__(self, clock: ClockPort) -> None:
        self._clock = clock

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement:
        logger.info(
            "tool request dispatched tool_request_id=%s workflow_instance_id=%s agent_task_id=%s "
            "tool_name=%s preceding_checkpoint_id=%s",
            request.id, request.workflow_instance_id, request.agent_task_id, request.tool_name, request.preceding_checkpoint_id,
        )
        return ToolDispatchAcknowledgement(request.id, ToolRequestStatus.DISPATCHED, self._clock.now())


class HttpToolGatewayPort:
    def __init__(
        self, base_url: str, clock: ClockPort, self_service_capability: str,
        http_client: httpx.Client | None = None, caller_id: str = "agent-runtime-service",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._clock = clock
        self._self_service_capability = self_service_capability
        self._client = http_client or httpx.Client(timeout=30.0)
        self._headers = {"X-Caller-Id": caller_id, "X-Caller-Type": "SERVICE", "Content-Type": "application/json"}

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement:
        now = self._clock.now()
        try:
            gateway_id, status = self._submit(request)
            if status == "QUEUED":
                status, result_payload, failure_reason = self._execute(gateway_id)
            else:
                result_payload = failure_reason = None
        except (httpx.HTTPError, KeyError, ValueError):
            # domain-rules fail-open: an unreachable/misbehaving gateway must never take
            # down the confirmation turn. Leave the request DISPATCHED — the workflow
            # keeps WAITING_FOR_TOOL and RecoverStaleToolWaitsService times it out.
            logger.warning("tool gateway dispatch failed for tool_request_id=%s", request.id, exc_info=True)
            return ToolDispatchAcknowledgement(request.id, ToolRequestStatus.DISPATCHED, now)

        if status in _TERMINAL_OK:
            return ToolDispatchAcknowledgement(
                request.id, ToolRequestStatus.COMPLETED, now, result_payload=result_payload or "",
            )
        if status in _TERMINAL_FAIL:
            return ToolDispatchAcknowledgement(
                request.id, ToolRequestStatus.FAILED, now,
                failure_reason=failure_reason or f"tool gateway returned {status}",
            )
        # PENDING_APPROVAL, still QUEUED after a retry backoff, etc. — non-terminal.
        return ToolDispatchAcknowledgement(request.id, ToolRequestStatus.DISPATCHED, now)

    def _submit(self, request: ToolRequestRecord) -> tuple[str, str]:
        try:
            input_payload = json.loads(request.request_payload) if request.request_payload else {}
        except ValueError:
            input_payload = {"raw": request.request_payload}
        body = {
            "idempotency_key": str(request.id),
            "requested_by_type": "AGENT",
            "requested_by_id": str(request.agent_task_id),
            "capability_name": self._self_service_capability,
            "input_payload": input_payload,
            "reason": "employee-confirmed self-service action",
            "correlation_id": str(request.id),
            "workflow_instance_id": str(request.workflow_instance_id),
            "agent_task_id": str(request.agent_task_id),
            "tool_name": request.tool_name,
        }
        response = self._client.post(f"{self._base_url}/internal/tool-gateway/v1/tool-requests", json=body, headers=self._headers)
        response.raise_for_status()
        data = response.json()
        return str(data["tool_request_id"]), str(data["status"])

    def _execute(self, gateway_id: str) -> tuple[str, str | None, str | None]:
        response = self._client.post(
            f"{self._base_url}/internal/tool-gateway/v1/tool-requests/{gateway_id}/execute",
            json={"correlation_id": gateway_id}, headers=self._headers,
        )
        response.raise_for_status()
        data = response.json()
        output = data.get("output")
        result_payload = json.dumps(output) if output is not None else None
        return str(data["status"]), result_payload, data.get("failure_reason")
