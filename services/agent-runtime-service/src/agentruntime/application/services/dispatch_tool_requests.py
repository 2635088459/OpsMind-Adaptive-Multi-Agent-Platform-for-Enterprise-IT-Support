"""13-package-and-class-design §"Application Layer" analogue for Tool Gateway dispatch:
DispatchToolRequestsService, the sole implementation of ToolDispatchPort and the sole
application service allowed to depend on ToolGatewayPort (enforced by
tests/architecture/test_tool_gateway_boundary.py) — RequestToolService held that role
before SPEC-ARO-019 moved it here.

SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: "插入需要 Tool
Gateway adapter 发送的 outbox command" / "Tool Gateway 调用不能在事务内直接同步执行" — this
service is that adapter-facing step, scanning for Tool Requests RequestToolService already
committed in PENDING state and dispatching them outside of and after that transaction.
Mirrors DispatchOutboxEventsService's own shape and its "nothing schedules this
periodically yet" caveat: invoked on demand through
POST /internal/agent-runtime/v1/admin/tool-requests/dispatch until a real scheduler exists
(phase-07, runtime-event-publishing, covers the analogous outbox case; the same gap
applies here).
"""

from __future__ import annotations

import dataclasses
import json
import logging

from agentruntime.application.ports_out import ClockPort, ToolGatewayPort, ToolRequestRepository
from agentruntime.application.services.consume_tool_result import ConsumeToolResultService
from agentruntime.application.views import DispatchToolRequestsReport
from agentruntime.domain.enums import ToolRequestStatus

logger = logging.getLogger(__name__)

_DEFAULT_BATCH_SIZE = 50
_TERMINAL = {ToolRequestStatus.COMPLETED, ToolRequestStatus.FAILED}


class DispatchToolRequestsService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, tool_gateway_port: ToolGatewayPort, clock: ClockPort,
        consume_tool_result_service: ConsumeToolResultService,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._tool_gateway_port = tool_gateway_port
        self._clock = clock
        self._consume_tool_result_service = consume_tool_result_service

    def dispatch_pending_requests(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> DispatchToolRequestsReport:
        now = self._clock.now()
        pending = self._tool_request_repository.find_pending(batch_size)

        dispatched = 0
        for record in pending:
            acknowledgement = self._tool_gateway_port.dispatch(record)
            dispatched += 1

            if acknowledgement.status not in _TERMINAL:
                self._tool_request_repository.save(dataclasses.replace(
                    record, status=acknowledgement.status, updated_at=acknowledgement.acknowledged_at,
                ))
                continue

            # phase-05 (tool-gateway-mediation): a real HTTP adapter can run the tool
            # synchronously and return a terminal outcome. Leave the record DISPATCHED
            # and let ConsumeToolResultService.apply() drive the terminal transition —
            # it only acts on a still-waiting request, so pre-saving COMPLETED here
            # would make apply() treat it as a duplicate and never wake the workflow.
            self._tool_request_repository.save(dataclasses.replace(
                record, status=ToolRequestStatus.DISPATCHED, updated_at=acknowledgement.acknowledged_at,
            ))
            try:
                self._consume_tool_result_service.apply(json.dumps({
                    "toolRequestId": str(record.id),
                    "status": "COMPLETED" if acknowledgement.status is ToolRequestStatus.COMPLETED else "FAILED",
                    "resultPayload": acknowledgement.result_payload
                    if acknowledgement.status is ToolRequestStatus.COMPLETED
                    else acknowledgement.failure_reason,
                }))
            except Exception:  # noqa: BLE001 — applying the result must not abort the batch
                logger.warning("failed to apply synchronous tool result for tool_request_id=%s", record.id, exc_info=True)

        return DispatchToolRequestsReport(scanned=len(pending), dispatched=dispatched, dispatched_at=now)
