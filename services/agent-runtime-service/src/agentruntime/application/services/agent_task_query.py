"""13-package-and-class-design §"Application Layer": AgentTaskQueryService, the sole
implementation of AgentTaskQueryPort. SPEC-ARO-006 05-api-contracts "GET
/agent-tasks/{agentTaskId}".
"""

from __future__ import annotations

from agentruntime.application.exceptions import AgentTaskNotFoundException
from agentruntime.application.ports_out import AgentTaskRepository
from agentruntime.application.views import AgentTaskView
from agentruntime.domain.ids import AgentTaskId


class AgentTaskQueryService:
    def __init__(self, agent_task_repository: AgentTaskRepository) -> None:
        self._agent_task_repository = agent_task_repository

    def find_agent_task(self, agent_task_id: AgentTaskId) -> AgentTaskView:
        record = self._agent_task_repository.find_by_id(agent_task_id)
        if record is None:
            raise AgentTaskNotFoundException(str(agent_task_id))
        return AgentTaskView.from_record(record)
