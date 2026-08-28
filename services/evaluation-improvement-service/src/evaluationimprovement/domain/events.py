"""Domain events raised by evaluationimprovement.domain.* aggregates. Frozen
dataclasses only — no framework dependency. Application services fold these into
application.records.OutboxRecord before appending to the outbox (domain-rules: "所有状态
迁移必须同事务写 audit/outbox"); 06-event-contracts names the corresponding wire
event_type for each.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from evaluationimprovement.domain.ids import CandidateId, ReportId, RunId


@dataclass(frozen=True, slots=True)
class EvaluationRunRequested:
    """06-event-contracts: `evaluation.run.requested.v1`."""

    run_id: RunId
    run_key: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class EvaluationRunCompleted:
    """06-event-contracts: `evaluation.run.completed.v1`."""

    run_id: RunId
    status: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class EvaluationGatePassed:
    """06-event-contracts: `evaluation.gate.passed.v1`."""

    run_id: RunId
    report_id: ReportId
    gate_policy: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class EvaluationGateFailed:
    """06-event-contracts: `evaluation.gate.failed.v1`."""

    run_id: RunId
    report_id: ReportId
    gate_policy: str
    critical_failure_count: int
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class EvaluationRegressionDetected:
    """06-event-contracts: `evaluation.regression.detected.v1`."""

    run_id: RunId
    report_id: ReportId
    regressed_dimensions: tuple[str, ...]
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class ImprovementCandidateCreated:
    """06-event-contracts: `improvement.candidate.created.v1`."""

    candidate_id: CandidateId
    candidate_type: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class ImprovementCandidateApproved:
    """06-event-contracts: `improvement.candidate.approved.v1`."""

    candidate_id: CandidateId
    approval_request_id: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class ImprovementRollbackRequested:
    """06-event-contracts: `improvement.rollback.requested.v1`."""

    candidate_id: CandidateId
    reason: str
    occurred_at: datetime
