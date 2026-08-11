"""Domain value objects (identifiers). Pure stdlib, frozen and self-validating,
mirroring the sibling Java services' record-per-value-object convention.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass


def _require_uuid(value: uuid.UUID, field_name: str) -> None:
    if not isinstance(value, uuid.UUID):
        raise TypeError(f"{field_name} must be a uuid.UUID")


@dataclass(frozen=True, slots=True)
class WorkflowInstanceId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "workflowInstanceId")

    @staticmethod
    def new_id() -> "WorkflowInstanceId":
        return WorkflowInstanceId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class AgentTaskId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "agentTaskId")

    @staticmethod
    def new_id() -> "AgentTaskId":
        return AgentTaskId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CheckpointId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "checkpointId")

    @staticmethod
    def new_id() -> "CheckpointId":
        return CheckpointId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ToolRequestId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "toolRequestId")

    @staticmethod
    def new_id() -> "ToolRequestId":
        return ToolRequestId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketId:
    """Runtime's own reference to a Ticket Workflow aggregate. 02-business-invariants
    §"State Ownership": Runtime may read Ticket snapshots through this id but must
    never use it to write Ticket lifecycle state directly.
    """

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketCycleId:
    """Part of the ticketId + ticketCycleId + workflowType uniqueness key (02-business-invariants)."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketCycleId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class WorkflowType:
    """Names a WorkflowDefinition family, e.g. "TICKET_TRIAGE"."""

    value: str

    def __post_init__(self) -> None:
        if not self.value or not self.value.strip():
            raise ValueError("workflowType must not be blank")
        if len(self.value) > 100:
            raise ValueError("workflowType must be at most 100 characters")

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class WorkflowDefinitionId:
    value: str

    def __post_init__(self) -> None:
        if not self.value or not self.value.strip():
            raise ValueError("workflowDefinitionId must not be blank")

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class DefinitionVersion:
    """02-business-invariants §"Workflow Instance Invariants": "A Workflow Instance must
    bind to definitionVersion; recovery must not silently switch definitions."
    """

    value: int

    def __post_init__(self) -> None:
        if self.value < 1:
            raise ValueError("definitionVersion must be at least 1")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class IdempotencyKey:
    """02-business-invariants §"Pause / Resume Idempotency Invariants": every Pause/Resume
    command must carry one.
    """

    value: str

    def __post_init__(self) -> None:
        if not self.value or not self.value.strip():
            raise ValueError("idempotencyKey must not be blank")
        if len(self.value) > 200:
            raise ValueError("idempotencyKey must be at most 200 characters")

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class LeaseToken:
    """13-package-and-class-design §"Class Boundaries": "Worker claim must use a lease"."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "leaseToken")

    @staticmethod
    def new_token() -> "LeaseToken":
        return LeaseToken(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CorrelationId:
    """SPEC-ARO-001 event-contract: every published/consumed event envelope must carry one."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "correlationId")

    @staticmethod
    def new_id() -> "CorrelationId":
        return CorrelationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CausationId:
    """SPEC-ARO-001 event-contract: every published/consumed event envelope must carry one."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "causationId")

    @staticmethod
    def new_id() -> "CausationId":
        return CausationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)
