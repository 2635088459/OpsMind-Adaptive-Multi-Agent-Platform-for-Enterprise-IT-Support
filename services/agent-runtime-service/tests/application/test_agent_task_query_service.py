from __future__ import annotations

from datetime import UTC, datetime

import pytest

from agentruntime.application.exceptions import AgentTaskNotFoundException
from agentruntime.application.records import AgentTaskRecord
from agentruntime.application.services.agent_task_query import AgentTaskQueryService
from agentruntime.domain.enums import AgentTaskState
from agentruntime.domain.ids import AgentTaskId, WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import InMemoryAgentTaskRepository

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    service = AgentTaskQueryService(agent_task_repository)
    return service, agent_task_repository


def test_finds_an_agent_task_by_id(wiring) -> None:
    service, agent_task_repository = wiring
    record = AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=WorkflowInstanceId.new_id(), task_key="collect",
        task_type="collect_diagnostics", depends_on_task_keys=frozenset(), state=AgentTaskState.PENDING, task_version=1,
        worker_id=None, lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None,
        pause_generation=0, created_at=NOW, updated_at=NOW,
    )
    agent_task_repository.save(record)

    view = service.find_agent_task(record.id)

    assert view.agent_task_id == record.id
    assert view.task_key == "collect"
    assert view.state is AgentTaskState.PENDING


def test_finding_an_unknown_agent_task_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(AgentTaskNotFoundException):
        service.find_agent_task(AgentTaskId.new_id())
