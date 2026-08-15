"""02-business-invariants §"Checkpoint Invariants": "A checkpoint must exist before
any external side effect" and "must not store secrets". This module only validates
the checkpoint's own shape; secret-scanning of the payload is a policy concern
layered on top in a later spec, not a rule this pure domain function can evaluate.

SPEC-ARO-011 01-domain-model/07-data-model: Checkpoint's own minimal-field list names
`workflowVersion` and `checksum` alongside `payloadJson`/`payloadSchemaVersion` — both
columns already existed on the `checkpoints` table (SPEC-ARO-002) but no writer ever
populated them, and the checksum SPEC-ARO-002's Postgres adapter computed was silently
discarded rather than round-tripped. Computing checksum here (not in the infrastructure
layer) keeps the in-memory and Postgres adapters behaviorally identical — every other
CheckpointRecord field is domain-computed-then-persisted-verbatim, and checksum is no
different: a deterministic hash of payload, not a per-adapter concern. `cursor` is the
one remaining minimal field this spec leaves at its existing None default: the
checkpoints.cursor column is reserved for phase-06 (external-event-consumption)'s
resumable-stream cursor, which no consumer produces yet — the parameter exists so a
future writer never needs to touch this function's signature again, not because this
spec has anything meaningful to put there.
"""

from __future__ import annotations

import hashlib
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
    *,
    workflow_version: int,
    cursor: str | None = None,
) -> CheckpointRecorded:
    if schema_version < 1:
        raise ValueError("schema_version must be at least 1")
    if not payload or not payload.strip():
        raise ValueError("payload must not be blank")
    if workflow_version < 1:
        raise ValueError("workflow_version must be at least 1")

    return CheckpointRecorded(
        checkpoint_id=checkpoint_id,
        workflow_instance_id=workflow_instance_id,
        type=type_,
        schema_version=schema_version,
        payload=payload,
        occurred_at=occurred_at,
        workflow_version=workflow_version,
        cursor=cursor,
        checksum=_compute_checksum(payload),
    )


def _compute_checksum(payload: str) -> str:
    """Plain integrity fingerprint of the payload as actually stored — not a
    cryptographic authentication tag (there is no secret key), just enough to let a
    human or a future recovery-hardening spec (phase-08) notice if a stored payload was
    altered or truncated out from under its recorded checksum.
    """
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()
