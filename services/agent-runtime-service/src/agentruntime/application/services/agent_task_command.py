"""Sole implementation of AgentTaskCommandPort: composes ClaimAgentTaskService,
CompleteAgentTaskService, and RequestToolService. request_tool is the only path
through which the interfaces layer can reach a Tool side effect, and it still goes
through RequestToolService — never ToolGatewayPort directly.
"""

from __future__ import annotations

from agentruntime.application.commands import ClaimAgentTaskCommand, ClaimReadyAgentTasksCommand, CompleteAgentTaskCommand, RequestToolCommand
from agentruntime.application.services.claim_agent_task import ClaimAgentTaskService
from agentruntime.application.services.complete_agent_task import CompleteAgentTaskService
from agentruntime.application.services.request_tool import RequestToolService
from agentruntime.application.views import AgentTaskView, ToolRequestView


class AgentTaskCommandService:
    def __init__(
        self,
        claim_agent_task_service: ClaimAgentTaskService,
        complete_agent_task_service: CompleteAgentTaskService,
        request_tool_service: RequestToolService,
    ) -> None:
        self._claim_agent_task_service = claim_agent_task_service
        self._complete_agent_task_service = complete_agent_task_service
        self._request_tool_service = request_tool_service

    def claim_agent_task(self, command: ClaimAgentTaskCommand) -> AgentTaskView:
        return self._claim_agent_task_service.claim(command)

    def claim_ready_agent_tasks(self, command: ClaimReadyAgentTasksCommand) -> list[AgentTaskView]:
        return self._claim_agent_task_service.claim_ready(command)

    def complete_agent_task(self, command: CompleteAgentTaskCommand) -> AgentTaskView:
        return self._complete_agent_task_service.complete(command)

    def request_tool(self, command: RequestToolCommand) -> ToolRequestView:
        return self._request_tool_service.request_tool(command)
