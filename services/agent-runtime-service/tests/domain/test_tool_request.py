from __future__ import annotations

from datetime import UTC, datetime

import pytest

from agentruntime.domain import tool_request
from agentruntime.domain.ids import AgentTaskId, CheckpointId, ToolRequestId, WorkflowInstanceId

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)


def test_create_without_a_preceding_checkpoint_is_unrepresentable() -> None:
    # 02-business-invariants §"Tool Gateway Boundary": a checkpoint must exist before any
    # external side effect.
    with pytest.raises(ValueError):
        tool_request.create(ToolRequestId.new_id(), WorkflowInstanceId.new_id(), AgentTaskId.new_id(), None, "restart_service", "{}", NOW)


def test_create_with_a_preceding_checkpoint_succeeds() -> None:
    event = tool_request.create(
        ToolRequestId.new_id(), WorkflowInstanceId.new_id(), AgentTaskId.new_id(), CheckpointId.new_id(), "restart_service", "{}", NOW
    )

    assert event.tool_name == "restart_service"
