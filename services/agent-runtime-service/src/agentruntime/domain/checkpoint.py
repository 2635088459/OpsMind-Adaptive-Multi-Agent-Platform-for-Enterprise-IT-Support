"""02-business-invariants §"Checkpoint Invariants": "A checkpoint must exist before
any external side effect" and "must not store secrets". This module only validates
the checkpoint's own shape; secret-scanning of the payload is a policy concern
layered on top in a later spec, not a rule this pure domain function can evaluate.
"""

from __future__ import annotations

from datetime import datetime

from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.events import CheckpointRecorded
from agentruntime.domain.ids import CheckpointId, WorkflowInstanceId


def record(
    checkpoint_id: CheckpointId,
    workflow_instance_id: WorkflowInstanceId,
    type_: CheckpointType,
    schema_version: int,
    payload: str,
    occurred_at: datetime,
) -> CheckpointRecorded:
    if schema_version < 1:
        raise ValueError("schema_version must be at least 1")
    if not payload or not payload.strip():
        raise ValueError("payload must not be blank")

    return CheckpointRecorded(
        checkpoint_id=checkpoint_id,
        workflow_instance_id=workflow_instance_id,
        type=type_,
        schema_version=schema_version,
        payload=payload,
        occurred_at=occurred_at,
    )
