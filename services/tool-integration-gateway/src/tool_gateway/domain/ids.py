"""Domain value objects (identifiers). Pure stdlib, frozen and self-validating —
mirrors memory-knowledge-service's own domain.ids convention and the Java
sibling services' record-per-value-object pattern.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass


def _require_uuid(value: uuid.UUID, field_name: str) -> None:
    if not isinstance(value, uuid.UUID):
        raise TypeError(f"{field_name} must be a uuid.UUID")


def _require_non_blank(value: str, field_name: str, max_length: int = 200) -> None:
    if not value or not value.strip():
        raise ValueError(f"{field_name} must not be blank")
    if len(value) > max_length:
        raise ValueError(f"{field_name} must be at most {max_length} characters")


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
class ToolExecutionId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "executionId")

    @staticmethod
    def new_id() -> "ToolExecutionId":
        return ToolExecutionId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ConnectorId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "connectorId")

    @staticmethod
    def new_id() -> "ConnectorId":
        return ConnectorId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ResultEnvelopeId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "resultEnvelopeId")

    @staticmethod
    def new_id() -> "ResultEnvelopeId":
        return ResultEnvelopeId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CredentialBindingId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "credentialBindingId")

    @staticmethod
    def new_id() -> "CredentialBindingId":
        return CredentialBindingId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ApprovalRequestId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "approvalRequestId")

    @staticmethod
    def new_id() -> "ApprovalRequestId":
        return ApprovalRequestId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketId:
    """05 does not own Ticket state (INV-TG-002) — this is a read-only reference."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TicketCycleId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "ticketCycleId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class WorkflowInstanceId:
    """05 does not own Workflow state (INV-TG-002) — this is a read-only reference."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "workflowInstanceId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class AgentTaskId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "agentTaskId")

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class IdempotencyKey:
    """01-domain-model §"ToolRequest": "idempotencyKey: business idempotency key
    supplied by Runtime."
    """

    value: str

    def __post_init__(self) -> None:
        _require_non_blank(self.value, "idempotencyKey")

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class OperationKey:
    """01-domain-model §"ToolExecution": "operationKey is the side-effect
    idempotency key passed to or simulated around the connector."
    """

    value: str

    def __post_init__(self) -> None:
        _require_non_blank(self.value, "operationKey", max_length=500)

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class CorrelationId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "correlationId")

    @staticmethod
    def new_id() -> "CorrelationId":
        return CorrelationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)
