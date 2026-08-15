from __future__ import annotations

from datetime import UTC, datetime

import pytest

from agentruntime.domain import checkpoint
from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.ids import CheckpointId, WorkflowInstanceId

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)


def test_record_produces_a_checkpoint_recorded_event() -> None:
    event = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, '{"step":1}', NOW, workflow_version=3
    )

    assert event.type is CheckpointType.PRE_TOOL_CALL
    assert event.schema_version == 1
    assert event.workflow_version == 3


def test_blank_payload_is_rejected() -> None:
    with pytest.raises(ValueError):
        checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, "   ", NOW, workflow_version=1)


def test_schema_version_below_one_is_rejected() -> None:
    with pytest.raises(ValueError):
        checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 0, "payload", NOW, workflow_version=1)


def test_workflow_version_below_one_is_rejected() -> None:
    """SPEC-ARO-011 01-domain-model: workflow_version is one of Checkpoint's own minimal
    fields — a checkpoint always belongs to some already-started (workflow_version >= 1)
    Workflow Instance.
    """
    with pytest.raises(ValueError):
        checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, "payload", NOW, workflow_version=0)


def test_cursor_defaults_to_none() -> None:
    """SPEC-ARO-011: cursor is reserved for phase-06 (external-event-consumption); no
    writer today has a real value to put there.
    """
    event = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, "payload", NOW, workflow_version=1
    )

    assert event.cursor is None


def test_the_same_payload_always_produces_the_same_checksum() -> None:
    first = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, '{"step":1}', NOW, workflow_version=1
    )
    second = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.AFTER_TASK, 1, '{"step":1}', NOW, workflow_version=7
    )

    assert first.checksum == second.checksum


def test_a_different_payload_produces_a_different_checksum() -> None:
    first = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, '{"step":1}', NOW, workflow_version=1
    )
    second = checkpoint.record(
        CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, '{"step":2}', NOW, workflow_version=1
    )

    assert first.checksum != second.checksum
