"""02-business-invariants §"Tool Gateway Boundary": "Agents cannot call Tools
directly" and "Runtime must write checkpoints before external side effects."
preceding_checkpoint_id being a required, non-None parameter makes a
checkpoint-less tool request unrepresentable — the Application layer
(RequestToolService) must persist a Checkpoint first and pass its id here.
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.domain.events import ToolRequested
from agentruntime.domain.ids import AgentTaskId, CheckpointId, ToolRequestId, WorkflowInstanceId


def create(
    tool_request_id: ToolRequestId,
    workflow_instance_id: WorkflowInstanceId,
    agent_task_id: AgentTaskId,
    preceding_checkpoint_id: CheckpointId,
    tool_name: str,
    request_payload: str,
    occurred_at: datetime,
) -> ToolRequested:
    if preceding_checkpoint_id is None:
        raise ValueError(
            "preceding_checkpoint_id must not be None: every external side effect "
            "must be preceded by a recoverable checkpoint"
        )
    if not tool_name or not tool_name.strip():
        raise ValueError("tool_name must not be blank")

    return ToolRequested(
        tool_request_id=tool_request_id,
        workflow_instance_id=workflow_instance_id,
        agent_task_id=agent_task_id,
        preceding_checkpoint_id=preceding_checkpoint_id,
        tool_name=tool_name,
        request_payload=request_payload,
        occurred_at=occurred_at,
    )
