"""SPEC-EI-002/SPEC-EI-003: SQLAlchemy/Postgres-backed implementations of every
application.ports_out repository Protocol except OutboxDispatchPort's own
EventPublisherPort (still LoggingEventPublisherAdapter — see
infrastructure.event_publisher's own module docstring). SPEC-EI-002 built the six
core aggregate repositories (07-data-model's own literal table list) plus
GatePolicyRepository/CaseExecutionResultRepository (pragmatic extensions);
SPEC-EI-003 adds OutboxRepository, ProcessedEventRepository,
CommandIdempotencyRepository, and AuditRecordRepository (07-data-model's own
outbox/processed_event/audit_record names, plus CommandIdempotencyRepository as
another pragmatic extension — see infrastructure.persistence.postgres.models's own
docstring). Each repository opens one short-lived Session per call
(`with self._session_factory() as session:`) — real cross-repository transaction
boundaries land with a later spec once a use case needs to coordinate more than one
aggregate write per request.

CAS pattern: EvaluationDataset/EvaluationRun/ImprovementCandidate carry no version
int of their own (01-domain-model's own field lists) — each uses a status-based
compare-and-swap via `save(entity, expected_status)`, a real Core
`update() ... WHERE id = :id AND status = :expected_status` with the rowcount
checked (never SQLAlchemy ORM's `session.get()`-then-mutate-then-commit, which
generates no such predicate at all — see [[tech-stack-per-service]] for why that
matters), matching infrastructure.persistence.in_memory's own in-process equivalent
exactly.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session, sessionmaker

from evaluationimprovement.application.exceptions import DatasetVersionConflictException, OptimisticConcurrencyConflictException
from evaluationimprovement.application.records import (
    AuditRecordEntry,
    CaseExecutionLease,
    CaseExecutionResult,
    CommandIdempotencyRecord,
    GatePolicyConfig,
    JudgeBundleStatus,
    LangSmithLinkRecord,
    OnlineEvaluationSample,
    OutboxRecord,
    PoisonEventRecord,
    ProcessedEventRecord,
)
from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.enums import (
    CandidateStatus,
    CandidateType,
    CanaryStatus,
    CaseExecutionStatus,
    CaseQueueStatus,
    Criticality,
    DatasetStatus,
    EvaluationDimension,
    GateDecision,
    GraderType,
    OnlineSampleStatus,
    OutboxStatus,
    RiskLevel,
    RunStatus,
    ScoreFailureCode,
)
from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import CandidateId, CorrelationId, DatasetId, IdempotencyKey, ReportId, RunId, ScoreId, TestCaseId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate
from evaluationimprovement.domain.regression_report import RegressionReport
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.domain.values import CanaryPlan, CanaryStage, EvidenceRef, GateResult, MetricDiff, VersionBinding
from evaluationimprovement.infrastructure.persistence.postgres.models import (
    AuditRecordRow,
    CaseExecutionQueueRow,
    CaseExecutionResultRow,
    CommandIdempotencyRow,
    EvaluationDatasetRow,
    EvaluationRunRow,
    EvaluationScoreRow,
    EvaluationTestCaseRow,
    GatePolicyRow,
    ImprovementCandidateRow,
    JudgeBundleStatusRow,
    LangSmithRunLinkRow,
    OnlineEvaluationSampleRow,
    OutboxEventRow,
    PoisonEventRow,
    ProcessedEventRow,
    RegressionReportRow,
)


def _float(value) -> float:  # noqa: ANN001
    return float(value) if isinstance(value, Decimal) else value


# --------------------------------------------------------------------------------
# EvaluationDataset
# --------------------------------------------------------------------------------


def _row_to_dataset(row: EvaluationDatasetRow) -> EvaluationDataset:
    return EvaluationDataset(
        dataset_id=DatasetId(row.id), name=row.name, version=row.version, domain=row.domain,
        scenario_tags=tuple(row.scenario_tags_json), status=DatasetStatus[row.status], case_count=row.case_count,
        lineage_parent_id=DatasetId(row.lineage_parent_id) if row.lineage_parent_id else None, created_by=row.created_by,
        created_at=row.created_at_domain, published_by=row.published_by, published_at=row.published_at,
        content_hash=row.content_hash, tenant_id=row.tenant_id,
    )


def _dataset_to_row_values(dataset: EvaluationDataset) -> dict:
    return dict(
        id=dataset.dataset_id.value, name=dataset.name, version=dataset.version, domain=dataset.domain,
        scenario_tags_json=list(dataset.scenario_tags), status=dataset.status.name, case_count=dataset.case_count,
        lineage_parent_id=dataset.lineage_parent_id.value if dataset.lineage_parent_id else None,
        created_by=dataset.created_by, published_by=dataset.published_by, created_at_domain=dataset.created_at,
        published_at=dataset.published_at, content_hash=dataset.content_hash, tenant_id=dataset.tenant_id,
    )


class PostgresDatasetRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, dataset_id: DatasetId) -> EvaluationDataset | None:
        with self._session_factory() as session:
            row = session.get(EvaluationDatasetRow, dataset_id.value)
            return _row_to_dataset(row) if row else None

    def find_by_name_version(self, name: str, version: str) -> EvaluationDataset | None:
        with self._session_factory() as session:
            stmt = select(EvaluationDatasetRow).where(EvaluationDatasetRow.name == name, EvaluationDatasetRow.version == version)
            row = session.execute(stmt).scalars().first()
            return _row_to_dataset(row) if row else None

    def save(self, dataset: EvaluationDataset, expected_status: DatasetStatus | None) -> EvaluationDataset:
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(EvaluationDatasetRow.__table__.insert().values(**_dataset_to_row_values(dataset)))
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise DatasetVersionConflictException(dataset.name, dataset.version) from exc
            else:
                values = {k: v for k, v in _dataset_to_row_values(dataset).items() if k != "id"}
                result = session.execute(
                    update(EvaluationDatasetRow.__table__)
                    .where(EvaluationDatasetRow.id == dataset.dataset_id.value, EvaluationDatasetRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException(f"dataset {dataset.dataset_id}")
                session.commit()
            return dataset

    def list_published(self, domain: str | None, tenant_id: str, limit: int) -> list[EvaluationDataset]:
        with self._session_factory() as session:
            stmt = select(EvaluationDatasetRow).where(
                EvaluationDatasetRow.status == DatasetStatus.PUBLISHED.name, EvaluationDatasetRow.tenant_id == tenant_id,
            )
            if domain is not None:
                stmt = stmt.where(EvaluationDatasetRow.domain == domain)
            stmt = stmt.order_by(EvaluationDatasetRow.created_at_domain.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_dataset(r) for r in rows]

    def find_versions(self, name: str, tenant_id: str) -> list[EvaluationDataset]:
        with self._session_factory() as session:
            stmt = (
                select(EvaluationDatasetRow)
                .where(EvaluationDatasetRow.name == name, EvaluationDatasetRow.tenant_id == tenant_id)
                .order_by(EvaluationDatasetRow.created_at_domain)
            )
            rows = session.execute(stmt).scalars().all()
            return [_row_to_dataset(r) for r in rows]


# --------------------------------------------------------------------------------
# EvaluationTestCase
# --------------------------------------------------------------------------------


def _row_to_test_case(row: EvaluationTestCaseRow) -> EvaluationTestCase:
    return EvaluationTestCase(
        test_case_id=TestCaseId(row.id), dataset_id=DatasetId(row.dataset_id), case_key=row.case_key, scenario=row.scenario,
        user_request_redacted=row.user_request_redacted, mock_system_state=row.mock_system_state_json,
        ground_truth=row.ground_truth_json, allowed_tools=tuple(row.allowed_tools_json),
        forbidden_tools=tuple(row.forbidden_tools_json), required_approval=row.required_approval,
        verification_condition=row.verification_condition_json, criticality=Criticality[row.criticality],
        input_hash=row.input_hash,
    )


def _test_case_to_row_values(case: EvaluationTestCase) -> dict:
    return dict(
        id=case.test_case_id.value, dataset_id=case.dataset_id.value, case_key=case.case_key, scenario=case.scenario,
        user_request_redacted=case.user_request_redacted, mock_system_state_json=case.mock_system_state,
        ground_truth_json=case.ground_truth, allowed_tools_json=list(case.allowed_tools),
        forbidden_tools_json=list(case.forbidden_tools), required_approval=case.required_approval,
        verification_condition_json=case.verification_condition, criticality=case.criticality.name,
        input_hash=case.input_hash,
    )


class PostgresTestCaseRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, test_case_id: TestCaseId) -> EvaluationTestCase | None:
        with self._session_factory() as session:
            row = session.get(EvaluationTestCaseRow, test_case_id.value)
            return _row_to_test_case(row) if row else None

    def find_by_dataset(self, dataset_id: DatasetId) -> list[EvaluationTestCase]:
        with self._session_factory() as session:
            stmt = select(EvaluationTestCaseRow).where(EvaluationTestCaseRow.dataset_id == dataset_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_test_case(r) for r in rows]

    def find_by_natural_key(self, dataset_id: DatasetId, case_key: str) -> EvaluationTestCase | None:
        with self._session_factory() as session:
            stmt = select(EvaluationTestCaseRow).where(
                EvaluationTestCaseRow.dataset_id == dataset_id.value, EvaluationTestCaseRow.case_key == case_key,
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_test_case(row) if row else None

    def save_many(self, cases: tuple[EvaluationTestCase, ...]) -> None:
        if not cases:
            return
        with self._session_factory() as session:
            session.execute(EvaluationTestCaseRow.__table__.insert(), [_test_case_to_row_values(c) for c in cases])
            session.commit()


# --------------------------------------------------------------------------------
# EvaluationRun
# --------------------------------------------------------------------------------


def _row_to_run(row: EvaluationRunRow) -> EvaluationRun:
    binding = VersionBinding(
        dataset_version=row.dataset_version, target_version=row.target_version,
        grader_bundle_version=row.grader_bundle_version, policy_version=row.policy_version,
        correlation_id=row.correlation_id, baseline_version=row.baseline_version,
    )
    return EvaluationRun(
        run_id=RunId(row.id), run_key=row.run_key, dataset_id=DatasetId(row.dataset_id), version_binding=binding,
        status=RunStatus[row.status], triggered_by=row.triggered_by, started_at=row.started_at, completed_at=row.completed_at,
    )


def _run_to_row_values(run: EvaluationRun) -> dict:
    return dict(
        id=run.run_id.value, run_key=run.run_key, dataset_id=run.dataset_id.value,
        dataset_version=run.version_binding.dataset_version, target_version=run.version_binding.target_version,
        baseline_version=run.version_binding.baseline_version, grader_bundle_version=run.version_binding.grader_bundle_version,
        policy_version=run.version_binding.policy_version, correlation_id=run.version_binding.correlation_id,
        status=run.status.name, triggered_by=run.triggered_by, started_at=run.started_at, completed_at=run.completed_at,
    )


class PostgresEvaluationRunRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, run_id: RunId) -> EvaluationRun | None:
        with self._session_factory() as session:
            row = session.get(EvaluationRunRow, run_id.value)
            return _row_to_run(row) if row else None

    def find_by_run_key(self, run_key: str) -> EvaluationRun | None:
        with self._session_factory() as session:
            stmt = select(EvaluationRunRow).where(EvaluationRunRow.run_key == run_key)
            row = session.execute(stmt).scalars().first()
            return _row_to_run(row) if row else None

    def save(self, run: EvaluationRun, expected_status: RunStatus | None) -> EvaluationRun:
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(EvaluationRunRow.__table__.insert().values(**_run_to_row_values(run)))
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException(f"run key {run.run_key}") from exc
            else:
                values = {k: v for k, v in _run_to_row_values(run).items() if k != "id"}
                result = session.execute(
                    update(EvaluationRunRow.__table__)
                    .where(EvaluationRunRow.id == run.run_id.value, EvaluationRunRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException(f"evaluation run {run.run_id}")
                session.commit()
            return run

    def current_generation(self, run_id: RunId) -> int:
        with self._session_factory() as session:
            row = session.get(EvaluationRunRow, run_id.value)
            return row.generation if row is not None else 0

    def find_by_dataset(self, dataset_id: DatasetId, status: RunStatus | None, limit: int) -> list[EvaluationRun]:
        with self._session_factory() as session:
            stmt = select(EvaluationRunRow).where(EvaluationRunRow.dataset_id == dataset_id.value)
            if status is not None:
                stmt = stmt.where(EvaluationRunRow.status == status.name)
            stmt = stmt.order_by(EvaluationRunRow.started_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_run(r) for r in rows]

    def find_stuck(self, statuses: frozenset[RunStatus], older_than: datetime) -> list[EvaluationRun]:
        with self._session_factory() as session:
            stmt = select(EvaluationRunRow).where(
                EvaluationRunRow.status.in_([s.name for s in statuses]), EvaluationRunRow.started_at < older_than,
            ).order_by(EvaluationRunRow.started_at)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_run(r) for r in rows]


# --------------------------------------------------------------------------------
# EvaluationScore
# --------------------------------------------------------------------------------


def _evidence_ref_to_json(ref: EvidenceRef | None) -> dict | None:
    if ref is None:
        return None
    return dict(
        artifact_provider=ref.artifact_provider, artifact_uri=ref.artifact_uri, artifact_hash=ref.artifact_hash,
        retention_until=ref.retention_until,
    )


def _json_to_evidence_ref(data: dict | None) -> EvidenceRef | None:
    return EvidenceRef(**data) if data is not None else None


def _row_to_score(row: EvaluationScoreRow) -> EvaluationScore:
    return EvaluationScore(
        score_id=ScoreId(row.id), run_id=RunId(row.run_id), test_case_id=TestCaseId(row.test_case_id),
        dimension=EvaluationDimension[row.dimension], score=_float(row.score), passed=row.passed,
        threshold=_float(row.threshold), grader_type=GraderType[row.grader_type], grader_version=row.grader_version,
        evidence_ref=_json_to_evidence_ref(row.evidence_ref_json),
        failure_code=ScoreFailureCode[row.failure_code] if row.failure_code else None, details=row.details_json,
        is_active=row.is_active,
    )


def _score_to_row_values(score: EvaluationScore) -> dict:
    return dict(
        id=score.score_id.value, run_id=score.run_id.value, test_case_id=score.test_case_id.value,
        dimension=score.dimension.name, score=score.score, passed=score.passed, threshold=score.threshold,
        grader_type=score.grader_type.name, grader_version=score.grader_version,
        evidence_ref_json=_evidence_ref_to_json(score.evidence_ref),
        failure_code=score.failure_code.name if score.failure_code else None, details_json=score.details,
        is_active=score.is_active,
    )


class PostgresScoreRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, score: EvaluationScore) -> EvaluationScore:
        """02-business-invariants INV-EI-007: append-only. Any existing ACTIVE row
        for (run_id, test_case_id, dimension) is superseded (is_active=False) in the
        same transaction as the new row's insert — the partial unique index
        (uq_evaluation_scores_one_active_per_case_dimension) is the DB-level backstop.
        """
        with self._session_factory() as session:
            self._save_one(session, score)
            session.commit()
            return score

    def save_many(self, scores: tuple[EvaluationScore, ...]) -> tuple[EvaluationScore, ...]:
        """SPEC-EI-017: one transaction for the whole batch — every score
        supersede-then-insert pair lands together, or (on any error) none of them do.
        """
        if not scores:
            return scores
        with self._session_factory() as session:
            for score in scores:
                self._save_one(session, score)
            session.commit()
            return scores

    def _save_one(self, session: Session, score: EvaluationScore) -> None:
        session.execute(
            update(EvaluationScoreRow.__table__)
            .where(
                EvaluationScoreRow.run_id == score.run_id.value, EvaluationScoreRow.test_case_id == score.test_case_id.value,
                EvaluationScoreRow.dimension == score.dimension.name, EvaluationScoreRow.is_active.is_(True),
            )
            .values(is_active=False)
        )
        session.execute(EvaluationScoreRow.__table__.insert().values(**_score_to_row_values(score)))

    def find_active_by_run(self, run_id: RunId) -> list[EvaluationScore]:
        with self._session_factory() as session:
            stmt = select(EvaluationScoreRow).where(EvaluationScoreRow.run_id == run_id.value, EvaluationScoreRow.is_active.is_(True))
            rows = session.execute(stmt).scalars().all()
            return [_row_to_score(r) for r in rows]

    def find_active(self, run_id: RunId, test_case_id: TestCaseId, dimension: EvaluationDimension) -> EvaluationScore | None:
        with self._session_factory() as session:
            stmt = select(EvaluationScoreRow).where(
                EvaluationScoreRow.run_id == run_id.value, EvaluationScoreRow.test_case_id == test_case_id.value,
                EvaluationScoreRow.dimension == dimension.name, EvaluationScoreRow.is_active.is_(True),
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_score(row) if row else None

    def count_distinct_scored_cases(self, run_id: RunId) -> int:
        with self._session_factory() as session:
            stmt = select(func.count(func.distinct(EvaluationScoreRow.test_case_id))).where(
                EvaluationScoreRow.run_id == run_id.value, EvaluationScoreRow.is_active.is_(True),
            )
            return session.execute(stmt).scalar_one()


# --------------------------------------------------------------------------------
# RegressionReport
# --------------------------------------------------------------------------------


def _row_to_report(row: RegressionReportRow) -> RegressionReport:
    return RegressionReport(
        report_id=ReportId(row.id), run_id=RunId(row.run_id),
        baseline_run_id=RunId(row.baseline_run_id) if row.baseline_run_id else None,
        overall_decision=GateDecision[row.overall_decision],
        metric_diffs=tuple(MetricDiff(**d) for d in row.metric_diffs_json),
        gate_results=tuple(GateResult(**g) for g in row.gate_results_json),
        critical_failures=tuple(row.critical_failures_json), recommendation=row.recommendation,
        created_at=row.created_at_domain,
    )


class PostgresRegressionReportRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, report: RegressionReport) -> RegressionReport:
        with self._session_factory() as session:
            session.execute(RegressionReportRow.__table__.insert().values(
                id=report.report_id.value, run_id=report.run_id.value,
                baseline_run_id=report.baseline_run_id.value if report.baseline_run_id else None,
                overall_decision=report.overall_decision.name,
                metric_diffs_json=[
                    dict(dimension=d.dimension, baseline_value=d.baseline_value, candidate_value=d.candidate_value)
                    for d in report.metric_diffs
                ],
                gate_results_json=[dict(gate_name=g.gate_name, passed=g.passed, reason=g.reason) for g in report.gate_results],
                critical_failures_json=list(report.critical_failures), recommendation=report.recommendation,
                created_at_domain=report.created_at,
            ))
            session.commit()
            return report

    def find_by_id(self, report_id: ReportId) -> RegressionReport | None:
        with self._session_factory() as session:
            row = session.get(RegressionReportRow, report_id.value)
            return _row_to_report(row) if row else None

    def find_by_run(self, run_id: RunId) -> RegressionReport | None:
        with self._session_factory() as session:
            stmt = select(RegressionReportRow).where(RegressionReportRow.run_id == run_id.value).order_by(RegressionReportRow.created_at_domain.desc())
            row = session.execute(stmt).scalars().first()
            return _row_to_report(row) if row else None


# --------------------------------------------------------------------------------
# ImprovementCandidate
# --------------------------------------------------------------------------------


def _canary_plan_to_json(plan: CanaryPlan | None) -> dict | None:
    if plan is None:
        return None
    return dict(
        plan_version=plan.plan_version,
        stages=[
            dict(
                traffic_percent=s.traffic_percent, min_duration_minutes=s.min_duration_minutes,
                rollback_error_rate_threshold=s.rollback_error_rate_threshold, sample_size=s.sample_size,
            )
            for s in plan.stages
        ],
    )


def _json_to_canary_plan(data: dict | None) -> CanaryPlan | None:
    if data is None:
        return None
    return CanaryPlan(plan_version=data["plan_version"], stages=tuple(CanaryStage(**s) for s in data["stages"]))


def _row_to_candidate(row: ImprovementCandidateRow) -> ImprovementCandidate:
    return ImprovementCandidate(
        candidate_id=CandidateId(row.id), candidate_type=CandidateType[row.candidate_type], source_run_id=RunId(row.source_run_id),
        source_failure_cluster_id=row.source_failure_cluster_id, target_component=row.target_component,
        proposed_change=row.proposed_change_json, risk_level=RiskLevel[row.risk_level], status=CandidateStatus[row.status],
        created_by=row.created_by, created_at=row.created_at_domain, updated_at=row.updated_at_domain,
        benchmark_run_id=RunId(row.benchmark_run_id) if row.benchmark_run_id else None,
        benchmark_passed=row.benchmark_passed, approval_request_id=row.approval_request_id, approved_by=row.approved_by,
        canary_plan=_json_to_canary_plan(row.canary_plan_json), canary_status=CanaryStatus[row.canary_status] if row.canary_status else None,
        promoted_version=row.promoted_version,
    )


def _candidate_to_row_values(candidate: ImprovementCandidate) -> dict:
    return dict(
        id=candidate.candidate_id.value, candidate_type=candidate.candidate_type.name, source_run_id=candidate.source_run_id.value,
        source_failure_cluster_id=candidate.source_failure_cluster_id, target_component=candidate.target_component,
        proposed_change_json=candidate.proposed_change, risk_level=candidate.risk_level.name, status=candidate.status.name,
        created_by=candidate.created_by, benchmark_run_id=candidate.benchmark_run_id.value if candidate.benchmark_run_id else None,
        benchmark_passed=candidate.benchmark_passed,
        approval_request_id=candidate.approval_request_id, approved_by=candidate.approved_by,
        canary_plan_json=_canary_plan_to_json(candidate.canary_plan),
        canary_status=candidate.canary_status.name if candidate.canary_status else None,
        promoted_version=candidate.promoted_version, created_at_domain=candidate.created_at, updated_at_domain=candidate.updated_at,
    )


class PostgresImprovementCandidateRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_id(self, candidate_id: CandidateId) -> ImprovementCandidate | None:
        with self._session_factory() as session:
            row = session.get(ImprovementCandidateRow, candidate_id.value)
            return _row_to_candidate(row) if row else None

    def save(self, candidate: ImprovementCandidate, expected_status: CandidateStatus | None) -> ImprovementCandidate:
        with self._session_factory() as session:
            if expected_status is None:
                try:
                    session.execute(ImprovementCandidateRow.__table__.insert().values(**_candidate_to_row_values(candidate)))
                    session.commit()
                except IntegrityError as exc:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException(f"improvement candidate {candidate.candidate_id}") from exc
            else:
                values = {k: v for k, v in _candidate_to_row_values(candidate).items() if k != "id"}
                result = session.execute(
                    update(ImprovementCandidateRow.__table__)
                    .where(ImprovementCandidateRow.id == candidate.candidate_id.value, ImprovementCandidateRow.status == expected_status.name)
                    .values(**values)
                )
                if result.rowcount != 1:
                    session.rollback()
                    raise OptimisticConcurrencyConflictException(f"improvement candidate {candidate.candidate_id}")
                session.commit()
            return candidate

    def find_by_natural_key(self, source_run_id: RunId, source_failure_cluster_id: str | None, target_component: str) -> ImprovementCandidate | None:
        with self._session_factory() as session:
            stmt = select(ImprovementCandidateRow).where(
                ImprovementCandidateRow.source_run_id == source_run_id.value, ImprovementCandidateRow.target_component == target_component,
            )
            stmt = stmt.where(
                ImprovementCandidateRow.source_failure_cluster_id.is_(None)
                if source_failure_cluster_id is None
                else ImprovementCandidateRow.source_failure_cluster_id == source_failure_cluster_id
            )
            row = session.execute(stmt).scalars().first()
            return _row_to_candidate(row) if row else None

    def find_by_approval_request_id(self, approval_request_id: str) -> ImprovementCandidate | None:
        with self._session_factory() as session:
            stmt = select(ImprovementCandidateRow).where(ImprovementCandidateRow.approval_request_id == approval_request_id)
            row = session.execute(stmt).scalars().first()
            return _row_to_candidate(row) if row else None


# --------------------------------------------------------------------------------
# GatePolicyConfig (pragmatic extension — see models.py's own docstring)
# --------------------------------------------------------------------------------


def _row_to_gate_policy(row: GatePolicyRow) -> GatePolicyConfig:
    return GatePolicyConfig(
        gate_policy=row.gate_policy, dimension_thresholds=row.dimension_thresholds_json,
        critical_case_required=row.critical_case_required, max_policy_violations=row.max_policy_violations,
        max_forbidden_tool_calls=row.max_forbidden_tool_calls, max_unauthorized_memory_access=row.max_unauthorized_memory_access,
    )


class PostgresGatePolicyRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_name(self, gate_policy: str) -> GatePolicyConfig | None:
        with self._session_factory() as session:
            row = session.get(GatePolicyRow, gate_policy)
            return _row_to_gate_policy(row) if row else None

    def save(self, config: GatePolicyConfig) -> GatePolicyConfig:
        """Simple last-write-wins upsert — 05-api-contracts §"管理 API" `PUT
        /evaluation/gates/{gatePolicy}` is a plain admin config write, not a
        state-machine transition, so no CAS is needed here the way the aggregate
        repositories above need one.
        """
        values = dict(
            gate_policy=config.gate_policy, dimension_thresholds_json=config.dimension_thresholds,
            critical_case_required=config.critical_case_required, max_policy_violations=config.max_policy_violations,
            max_forbidden_tool_calls=config.max_forbidden_tool_calls,
            max_unauthorized_memory_access=config.max_unauthorized_memory_access,
        )
        with self._session_factory() as session:
            stmt = pg_insert(GatePolicyRow.__table__).values(**values)
            stmt = stmt.on_conflict_do_update(index_elements=["gate_policy"], set_={k: v for k, v in values.items() if k != "gate_policy"})
            session.execute(stmt)
            session.commit()
            return config


# --------------------------------------------------------------------------------
# CaseExecutionResult (pragmatic extension — see models.py's own docstring)
# --------------------------------------------------------------------------------


def _row_to_case_execution_result(row: CaseExecutionResultRow) -> CaseExecutionResult:
    return CaseExecutionResult(
        run_id=str(row.run_id), test_case_id=str(row.test_case_id), run_generation=row.run_generation,
        final_state=row.final_state, tool_calls=tuple(row.tool_calls_json), classification=row.classification,
        policy_violation_count=row.policy_violation_count, forbidden_tool_call_count=row.forbidden_tool_call_count,
        unauthorized_memory_access_count=row.unauthorized_memory_access_count, cost_tokens=row.cost_tokens,
        latency_ms=row.latency_ms, workflow_trace_ref=row.workflow_trace_ref, status=CaseExecutionStatus[row.status],
        failure_reason=row.failure_reason, approval_triggered=row.approval_triggered,
        verification_passed=row.verification_passed, tool_call_args=dict(row.tool_call_args_json),
        explanation_text=row.explanation_text,
    )


class PostgresCaseExecutionResultRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, result: CaseExecutionResult) -> None:
        """Upsert keyed by (run_id, test_case_id) — 09-concurrency-and-idempotency
        §"并发规则": "同一个 run 的同一个 case 可以重试", so a re-execution replaces the
        prior transient result in place rather than appending a new row.
        """
        values = dict(
            run_id=uuid.UUID(result.run_id), test_case_id=uuid.UUID(result.test_case_id), run_generation=result.run_generation,
            final_state=result.final_state, tool_calls_json=list(result.tool_calls), classification=result.classification,
            policy_violation_count=result.policy_violation_count, forbidden_tool_call_count=result.forbidden_tool_call_count,
            unauthorized_memory_access_count=result.unauthorized_memory_access_count, cost_tokens=result.cost_tokens,
            latency_ms=result.latency_ms, workflow_trace_ref=result.workflow_trace_ref, status=result.status.value,
            failure_reason=result.failure_reason, approval_triggered=result.approval_triggered,
            verification_passed=result.verification_passed, tool_call_args_json=dict(result.tool_call_args),
            explanation_text=result.explanation_text,
        )
        with self._session_factory() as session:
            stmt = pg_insert(CaseExecutionResultRow.__table__).values(**values)
            stmt = stmt.on_conflict_do_update(
                index_elements=["run_id", "test_case_id"], set_={k: v for k, v in values.items() if k not in ("run_id", "test_case_id")}
            )
            session.execute(stmt)
            session.commit()

    def find(self, run_id: RunId, test_case_id: TestCaseId) -> CaseExecutionResult | None:
        with self._session_factory() as session:
            row = session.get(CaseExecutionResultRow, {"run_id": run_id.value, "test_case_id": test_case_id.value})
            return _row_to_case_execution_result(row) if row else None

    def find_by_run(self, run_id: RunId) -> list[CaseExecutionResult]:
        with self._session_factory() as session:
            stmt = select(CaseExecutionResultRow).where(CaseExecutionResultRow.run_id == run_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_case_execution_result(r) for r in rows]


# --------------------------------------------------------------------------------
# CaseExecutionLease (SPEC-EI-011 — pragmatic extension, see models.py's own docstring)
# --------------------------------------------------------------------------------


def _row_to_case_execution_lease(row: CaseExecutionQueueRow) -> CaseExecutionLease:
    return CaseExecutionLease(
        run_id=str(row.run_id), test_case_id=str(row.test_case_id), run_generation=row.run_generation,
        status=CaseQueueStatus[row.status], attempt_count=row.attempt_count, next_attempt_at=row.next_attempt_at,
        leased_by=row.leased_by, leased_at=row.leased_at, lease_expires_at=row.lease_expires_at,
    )


class PostgresCaseExecutionQueueRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def enqueue_many(self, run_id: RunId, test_case_ids: tuple[TestCaseId, ...], run_generation: int, now: datetime) -> None:
        """09-concurrency-and-idempotency: `ON CONFLICT DO NOTHING` — a resubmitted
        enqueue for a pair already queued/leased/done leaves the existing row (and its
        in-flight attempt_count/status) completely untouched.
        """
        if not test_case_ids:
            return
        with self._session_factory() as session:
            stmt = pg_insert(CaseExecutionQueueRow.__table__).values([
                dict(
                    run_id=run_id.value, test_case_id=test_case_id.value, run_generation=run_generation,
                    status=CaseQueueStatus.PENDING.name, attempt_count=0, next_attempt_at=now,
                )
                for test_case_id in test_case_ids
            ])
            stmt = stmt.on_conflict_do_nothing(index_elements=["run_id", "test_case_id"])
            session.execute(stmt)
            session.commit()

    def find_claimable(self, now: datetime, limit: int) -> list[CaseExecutionLease]:
        with self._session_factory() as session:
            stmt = select(CaseExecutionQueueRow).where(
                CaseExecutionQueueRow.status == CaseQueueStatus.PENDING.name, CaseExecutionQueueRow.next_attempt_at <= now,
            ).order_by(CaseExecutionQueueRow.next_attempt_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_case_execution_lease(r) for r in rows]

    def claim(self, run_id: RunId, test_case_id: TestCaseId, worker_id: str, now: datetime, lease_expires_at: datetime) -> bool:
        with self._session_factory() as session:
            result = session.execute(
                update(CaseExecutionQueueRow.__table__)
                .where(
                    CaseExecutionQueueRow.run_id == run_id.value, CaseExecutionQueueRow.test_case_id == test_case_id.value,
                    CaseExecutionQueueRow.status == CaseQueueStatus.PENDING.name,
                )
                .values(status=CaseQueueStatus.LEASED.name, leased_by=worker_id, leased_at=now, lease_expires_at=lease_expires_at)
            )
            won = result.rowcount == 1
            session.commit()
            return won

    def mark_done(self, run_id: RunId, test_case_id: TestCaseId) -> None:
        self._set_status(run_id, test_case_id, CaseQueueStatus.DONE)

    def mark_retry(self, run_id: RunId, test_case_id: TestCaseId, next_attempt_at: datetime, attempt_count: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(CaseExecutionQueueRow.__table__)
                .where(CaseExecutionQueueRow.run_id == run_id.value, CaseExecutionQueueRow.test_case_id == test_case_id.value)
                .values(
                    status=CaseQueueStatus.PENDING.name, attempt_count=attempt_count, next_attempt_at=next_attempt_at,
                    leased_by=None, leased_at=None, lease_expires_at=None,
                )
            )
            session.commit()

    def mark_exhausted(self, run_id: RunId, test_case_id: TestCaseId, attempt_count: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(CaseExecutionQueueRow.__table__)
                .where(CaseExecutionQueueRow.run_id == run_id.value, CaseExecutionQueueRow.test_case_id == test_case_id.value)
                .values(status=CaseQueueStatus.EXHAUSTED.name, attempt_count=attempt_count)
            )
            session.commit()

    def find_expired_leases(self, now: datetime, limit: int) -> list[CaseExecutionLease]:
        with self._session_factory() as session:
            stmt = select(CaseExecutionQueueRow).where(
                CaseExecutionQueueRow.status == CaseQueueStatus.LEASED.name, CaseExecutionQueueRow.lease_expires_at < now,
            ).order_by(CaseExecutionQueueRow.lease_expires_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_case_execution_lease(r) for r in rows]

    def release_expired_lease(self, run_id: RunId, test_case_id: TestCaseId, next_attempt_at: datetime, attempt_count: int) -> bool:
        with self._session_factory() as session:
            result = session.execute(
                update(CaseExecutionQueueRow.__table__)
                .where(
                    CaseExecutionQueueRow.run_id == run_id.value, CaseExecutionQueueRow.test_case_id == test_case_id.value,
                    CaseExecutionQueueRow.status == CaseQueueStatus.LEASED.name,
                )
                .values(
                    status=CaseQueueStatus.PENDING.name, attempt_count=attempt_count, next_attempt_at=next_attempt_at,
                    leased_by=None, leased_at=None, lease_expires_at=None,
                )
            )
            won = result.rowcount == 1
            session.commit()
            return won

    def find_by_run(self, run_id: RunId) -> list[CaseExecutionLease]:
        with self._session_factory() as session:
            stmt = select(CaseExecutionQueueRow).where(CaseExecutionQueueRow.run_id == run_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_case_execution_lease(r) for r in rows]

    def _set_status(self, run_id: RunId, test_case_id: TestCaseId, status: CaseQueueStatus) -> None:
        with self._session_factory() as session:
            session.execute(
                update(CaseExecutionQueueRow.__table__)
                .where(CaseExecutionQueueRow.run_id == run_id.value, CaseExecutionQueueRow.test_case_id == test_case_id.value)
                .values(status=status.name)
            )
            session.commit()


# --------------------------------------------------------------------------------
# LangSmithLinkRecord (SPEC-EI-013 — pragmatic extension, see models.py's own docstring)
# --------------------------------------------------------------------------------


class PostgresLangSmithLinkRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, record: LangSmithLinkRecord) -> None:
        values = dict(run_id=uuid.UUID(record.run_id), enabled=record.enabled, experiment_ref=record.experiment_ref)
        with self._session_factory() as session:
            stmt = pg_insert(LangSmithRunLinkRow.__table__).values(**values)
            stmt = stmt.on_conflict_do_update(
                index_elements=["run_id"], set_={k: v for k, v in values.items() if k != "run_id"}
            )
            session.execute(stmt)
            session.commit()

    def find(self, run_id: RunId) -> LangSmithLinkRecord | None:
        with self._session_factory() as session:
            row = session.get(LangSmithRunLinkRow, run_id.value)
            if row is None:
                return None
            return LangSmithLinkRecord(run_id=str(row.run_id), enabled=row.enabled, experiment_ref=row.experiment_ref)


# --------------------------------------------------------------------------------
# JudgeBundleStatus (SPEC-EI-018 — pragmatic extension, see models.py's own docstring)
# --------------------------------------------------------------------------------


class PostgresJudgeBundleStatusRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_status(self, grader_version: str) -> JudgeBundleStatus | None:
        with self._session_factory() as session:
            row = session.get(JudgeBundleStatusRow, grader_version)
            if row is None:
                return None
            return JudgeBundleStatus(
                grader_version=row.grader_version, enabled=row.enabled, last_checked_at=row.last_checked_at,
                last_mean_absolute_error=_float(row.last_mean_absolute_error) if row.last_mean_absolute_error is not None else None,
                disabled_reason=row.disabled_reason,
            )

    def save_status(self, status: JudgeBundleStatus) -> JudgeBundleStatus:
        values = dict(
            grader_version=status.grader_version, enabled=status.enabled, last_checked_at=status.last_checked_at,
            last_mean_absolute_error=status.last_mean_absolute_error, disabled_reason=status.disabled_reason,
        )
        with self._session_factory() as session:
            stmt = pg_insert(JudgeBundleStatusRow.__table__).values(**values)
            stmt = stmt.on_conflict_do_update(
                index_elements=["grader_version"], set_={k: v for k, v in values.items() if k != "grader_version"}
            )
            session.execute(stmt)
            session.commit()
            return status


def _row_to_online_sample(row: OnlineEvaluationSampleRow) -> OnlineEvaluationSample:
    return OnlineEvaluationSample(
        sample_id=row.id, candidate_id=row.candidate_id, target_version=row.target_version,
        source_event_type=row.source_event_type, source_trace_ref=row.source_trace_ref,
        redacted_context=row.redacted_context_json, status=OnlineSampleStatus[row.status],
        collected_at=row.collected_at, scored_at=row.scored_at,
        composite_score=_float(row.composite_score) if row.composite_score is not None else None,
        score_details=row.score_details_json, failure_code=ScoreFailureCode[row.failure_code] if row.failure_code else None,
    )


def _online_sample_to_row_values(sample: OnlineEvaluationSample) -> dict:
    return dict(
        id=sample.sample_id, candidate_id=sample.candidate_id, target_version=sample.target_version,
        source_event_type=sample.source_event_type, source_trace_ref=sample.source_trace_ref,
        redacted_context_json=sample.redacted_context, status=sample.status.name, collected_at=sample.collected_at,
        scored_at=sample.scored_at, composite_score=sample.composite_score, score_details_json=sample.score_details,
        failure_code=sample.failure_code.name if sample.failure_code else None,
    )


class PostgresOnlineSampleRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save(self, sample: OnlineEvaluationSample) -> OnlineEvaluationSample:
        values = _online_sample_to_row_values(sample)
        with self._session_factory() as session:
            stmt = pg_insert(OnlineEvaluationSampleRow.__table__).values(**values)
            stmt = stmt.on_conflict_do_update(index_elements=["id"], set_={k: v for k, v in values.items() if k != "id"})
            session.execute(stmt)
            session.commit()
            return sample

    def find_by_id(self, sample_id: uuid.UUID) -> OnlineEvaluationSample | None:
        with self._session_factory() as session:
            row = session.get(OnlineEvaluationSampleRow, sample_id)
            return _row_to_online_sample(row) if row else None

    def find_queued(self, limit: int) -> list[OnlineEvaluationSample]:
        with self._session_factory() as session:
            stmt = select(OnlineEvaluationSampleRow).where(
                OnlineEvaluationSampleRow.status == OnlineSampleStatus.QUEUED.name,
            ).order_by(OnlineEvaluationSampleRow.collected_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_online_sample(r) for r in rows]

    def find_by_candidate(self, candidate_id: CandidateId) -> list[OnlineEvaluationSample]:
        with self._session_factory() as session:
            stmt = select(OnlineEvaluationSampleRow).where(OnlineEvaluationSampleRow.candidate_id == candidate_id.value)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_online_sample(r) for r in rows]


# --------------------------------------------------------------------------------
# OutboxRecord (SPEC-EI-003)
# --------------------------------------------------------------------------------


def _row_to_outbox_record(row: OutboxEventRow) -> OutboxRecord:
    # correlation_id is always written by append() as str(CorrelationId(...)) — a
    # real uuid.UUID's own string form — so it always parses back cleanly here.
    return OutboxRecord(
        outbox_id=row.id, event_type=row.event_type, schema_version=row.schema_version, aggregate_id=row.aggregate_id,
        payload=row.payload, occurred_at=row.occurred_at, correlation_id=CorrelationId(uuid.UUID(row.correlation_id)),
        status=OutboxStatus[row.status], attempts=row.attempts, available_at=row.available_at, published_at=row.published_at,
    )


class PostgresOutboxRepository:
    """SPEC-EI-003 / 08-transaction-and-outbox §"Outbox 发布". Real RabbitMQ wiring for
    EventPublisherPort itself stays deferred — see infrastructure.event_publisher's
    own module docstring — this repository is only the durable row lifecycle
    DispatchOutboxEventsService's existing publish/retry/backoff loop already drives.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, record: OutboxRecord) -> None:
        with self._session_factory() as session:
            session.execute(OutboxEventRow.__table__.insert().values(
                id=record.outbox_id, event_type=record.event_type, schema_version=record.schema_version,
                aggregate_id=record.aggregate_id, payload=record.payload, occurred_at=record.occurred_at,
                correlation_id=str(record.correlation_id), status=record.status.name, attempts=record.attempts,
                available_at=record.available_at, published_at=record.published_at,
            ))
            session.commit()

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        with self._session_factory() as session:
            stmt = select(OutboxEventRow).where(
                OutboxEventRow.status.in_((OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)),
                (OutboxEventRow.available_at.is_(None)) | (OutboxEventRow.available_at <= now),
            ).order_by(OutboxEventRow.occurred_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_outbox_record(r) for r in rows]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id)
                .values(status=OutboxStatus.PUBLISHED.name, published_at=published_at)
            )
            session.commit()

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._session_factory() as session:
            session.execute(
                update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id)
                .values(status=OutboxStatus.FAILED.name, available_at=next_available_at, attempts=attempts)
            )
            session.commit()

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._session_factory() as session:
            session.execute(update(OutboxEventRow.__table__).where(OutboxEventRow.id == outbox_id).values(status=OutboxStatus.DEAD_LETTER.name))
            session.commit()

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        with self._session_factory() as session:
            stmt = select(OutboxEventRow).where(OutboxEventRow.status == OutboxStatus.DEAD_LETTER.name).order_by(OutboxEventRow.occurred_at).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_outbox_record(r) for r in rows]


# --------------------------------------------------------------------------------
# ProcessedEventRecord (SPEC-EI-003)
# --------------------------------------------------------------------------------


class PostgresProcessedEventRepository:
    """SPEC-EI-003 / 09-concurrency-and-idempotency: "07 消费外部事件时写 processed_events."
    Not yet called by any real consumer in this service's own scope — see
    interfaces.event's own module docstring — wired ahead of its first consumer the
    same way memory-knowledge-service's own SPEC-MK-001 defined this port before
    SPEC-MK-010 first used it.
    """

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._session_factory() as session:
            row = session.get(ProcessedEventRow, {"event_id": event_id, "consumer_name": consumer_name})
            return row is not None

    def mark_processed(self, record: ProcessedEventRecord) -> None:
        with self._session_factory() as session:
            stmt = pg_insert(ProcessedEventRow.__table__).values(
                event_id=record.event_id, consumer_name=record.consumer_name, event_type=record.event_type,
                processed_at=record.processed_at,
            )
            # A duplicate delivery marking the same (event_id, consumer_name) as
            # processed a second time is exactly the dedup case this table exists
            # for — a no-op, never a conflict.
            stmt = stmt.on_conflict_do_nothing(index_elements=["event_id", "consumer_name"])
            session.execute(stmt)
            session.commit()


# --------------------------------------------------------------------------------
# CommandIdempotencyRecord (SPEC-EI-003)
# --------------------------------------------------------------------------------


def _row_to_command_idempotency_record(row: CommandIdempotencyRow) -> CommandIdempotencyRecord:
    return CommandIdempotencyRecord(
        idempotency_key=IdempotencyKey(row.idempotency_key), command_type=row.command_type, target_id=row.target_id,
        request_hash=row.request_hash, response_json=row.response_json, created_at=row.created_at,
    )


class PostgresCommandIdempotencyRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        with self._session_factory() as session:
            row = session.get(CommandIdempotencyRow, str(idempotency_key))
            return _row_to_command_idempotency_record(row) if row else None

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        with self._session_factory() as session:
            session.execute(CommandIdempotencyRow.__table__.insert().values(
                idempotency_key=str(record.idempotency_key), command_type=record.command_type, target_id=record.target_id,
                request_hash=record.request_hash, response_json=record.response_json, created_at=record.created_at,
            ))
            session.commit()
            return record


# --------------------------------------------------------------------------------
# AuditRecordEntry (SPEC-EI-003)
# --------------------------------------------------------------------------------


def _row_to_audit_record(row: AuditRecordRow) -> AuditRecordEntry:
    return AuditRecordEntry(
        id=row.id, action=row.action, resource_type=row.resource_type, resource_id=row.resource_id, actor=row.actor,
        outcome=row.outcome, correlation_id=row.correlation_id, detail=row.detail, occurred_at=row.occurred_at,
    )


class PostgresAuditRecordRepository:
    """SPEC-EI-003 / 12-observability §"Audit Events". Append-only."""

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def append(self, entry: AuditRecordEntry) -> None:
        with self._session_factory() as session:
            session.execute(AuditRecordRow.__table__.insert().values(
                id=entry.id, action=entry.action, resource_type=entry.resource_type, resource_id=entry.resource_id,
                actor=entry.actor, outcome=entry.outcome, correlation_id=entry.correlation_id, detail=entry.detail,
                occurred_at=entry.occurred_at,
            ))
            session.commit()

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        with self._session_factory() as session:
            stmt = select(AuditRecordRow).order_by(AuditRecordRow.occurred_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_audit_record(r) for r in rows]


def _row_to_poison_event(row: PoisonEventRow) -> PoisonEventRecord:
    return PoisonEventRecord(
        id=row.id, event_id=row.event_id, consumer_name=row.consumer_name, event_type=row.event_type, payload=row.payload,
        error_message=row.error_message, occurred_at=row.occurred_at, recorded_at=row.recorded_at,
    )


class PostgresPoisonEventRepository:
    """SPEC-EI-035. Append-only, mirrors PostgresAuditRecordRepository's own shape."""

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def record(self, entry: PoisonEventRecord) -> PoisonEventRecord:
        with self._session_factory() as session:
            session.execute(PoisonEventRow.__table__.insert().values(
                id=entry.id, event_id=entry.event_id, consumer_name=entry.consumer_name, event_type=entry.event_type,
                payload=entry.payload, error_message=entry.error_message, occurred_at=entry.occurred_at,
                recorded_at=entry.recorded_at,
            ))
            session.commit()
            return entry

    def find_all(self, limit: int) -> list[PoisonEventRecord]:
        with self._session_factory() as session:
            stmt = select(PoisonEventRow).order_by(PoisonEventRow.recorded_at.desc()).limit(limit)
            rows = session.execute(stmt).scalars().all()
            return [_row_to_poison_event(r) for r in rows]
