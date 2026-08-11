"""SPEC-ARO-001 module boundary placeholder for ToolGatewayPort —
13-package-and-class-design §"Adapters": "Tool Gateway adapter is Runtime's only exit
to tool systems." This adapter never calls a real tool: it only logs that a dispatch
happened and acknowledges it as DISPATCHED. phase-05 (tool-gateway-mediation)
replaces this with the real HTTP adapter (httpx, per the frozen Python Agent Runtime
baseline) that performs authorization, audit, rate limiting, and retry before any
external call.
"""

from __future__ import annotations

import logging

from agentruntime.application.ports_out import ClockPort
from agentruntime.application.records import ToolDispatchAcknowledgement, ToolRequestRecord
from agentruntime.domain.enums import ToolRequestStatus

logger = logging.getLogger(__name__)


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
