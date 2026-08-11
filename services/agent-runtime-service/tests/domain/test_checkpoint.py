from __future__ import annotations

from datetime import UTC, datetime

import pytest

from agentruntime.domain import checkpoint
from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.ids import CheckpointId, WorkflowInstanceId

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=UTC)


def test_record_produces_a_checkpoint_recorded_event() -> None:
    event = checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, '{"step":1}', NOW)

    assert event.type is CheckpointType.PRE_TOOL_CALL
    assert event.schema_version == 1


def test_blank_payload_is_rejected() -> None:
    with pytest.raises(ValueError):
        checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 1, "   ", NOW)


def test_schema_version_below_one_is_rejected() -> None:
    with pytest.raises(ValueError):
        checkpoint.record(CheckpointId.new_id(), WorkflowInstanceId.new_id(), CheckpointType.PRE_TOOL_CALL, 0, "payload", NOW)
