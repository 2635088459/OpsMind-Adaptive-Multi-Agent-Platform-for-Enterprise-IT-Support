"""Typed identifiers. Frozen, self-validating dataclasses wrapping uuid.UUID — mirrors
memory-knowledge-service's own domain.ids convention exactly (one explicit class per
identifier, rather than a generic factory, to match the sibling services' style).
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
class DatasetId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "datasetId")

    @staticmethod
    def new_id() -> "DatasetId":
        return DatasetId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class TestCaseId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "testCaseId")

    @staticmethod
    def new_id() -> "TestCaseId":
        return TestCaseId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class RunId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "runId")

    @staticmethod
    def new_id() -> "RunId":
        return RunId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ScoreId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "scoreId")

    @staticmethod
    def new_id() -> "ScoreId":
        return ScoreId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class ReportId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "reportId")

    @staticmethod
    def new_id() -> "ReportId":
        return ReportId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CandidateId:
    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "candidateId")

    @staticmethod
    def new_id() -> "CandidateId":
        return CandidateId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class CorrelationId:
    """06-event-contracts: every published/consumed event envelope must carry one."""

    value: uuid.UUID

    def __post_init__(self) -> None:
        _require_uuid(self.value, "correlationId")

    @staticmethod
    def new_id() -> "CorrelationId":
        return CorrelationId(uuid.uuid4())

    def __str__(self) -> str:
        return str(self.value)


@dataclass(frozen=True, slots=True)
class IdempotencyKey:
    """09-concurrency-and-idempotency §"幂等键": Dataset publish `dataset:{name}:
    {version}`, Run create `runKey`, Score write `runId:testCaseId:dimension:
    graderVersion`, Candidate create `sourceRunId:failureClusterId:targetComponent`,
    Canary operation `candidateId:canaryPlanVersion:operation`. Callers build the string
    per that scheme; this type only enforces non-blank.
    """

    value: str

    def __post_init__(self) -> None:
        _require_non_blank(self.value, "idempotencyKey")

    def __str__(self) -> str:
        return self.value
