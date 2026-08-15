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

from agentruntime.application.ports_out import ClockPort, ToolGatewayPort, ToolRequestRepository
from agentruntime.application.views import DispatchToolRequestsReport

_DEFAULT_BATCH_SIZE = 50


class DispatchToolRequestsService:
    def __init__(self, tool_request_repository: ToolRequestRepository, tool_gateway_port: ToolGatewayPort, clock: ClockPort) -> None:
        self._tool_request_repository = tool_request_repository
        self._tool_gateway_port = tool_gateway_port
        self._clock = clock

    def dispatch_pending_requests(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> DispatchToolRequestsReport:
        now = self._clock.now()
        pending = self._tool_request_repository.find_pending(batch_size)

        dispatched = 0
        for record in pending:
            acknowledgement = self._tool_gateway_port.dispatch(record)
            updated = dataclasses.replace(record, status=acknowledgement.status, updated_at=acknowledgement.acknowledged_at)
            self._tool_request_repository.save(updated)
            dispatched += 1

        return DispatchToolRequestsReport(scanned=len(pending), dispatched=dispatched, dispatched_at=now)
