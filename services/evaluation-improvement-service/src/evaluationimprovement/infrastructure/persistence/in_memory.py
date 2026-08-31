"""In-memory adapters for every application.ports_out Protocol. Real Postgres-backed
adapters are SPEC-EI-002 (evaluation-schema-baseline) scope — mirrors
memory-knowledge-service's own SPEC-MK-001/SPEC-MK-002 split exactly. Not toys: every
adapter here enforces the same uniqueness/compare-and-swap rules a real Postgres
schema's constraints would (07-data-model §"唯一键", 09-concurrency-and-idempotency
§"并发规则"), just backed by a plain dict instead of a table.
"""

from __future__ import annotations

import dataclasses
import threading
import uuid
from datetime import datetime

from evaluationimprovement.application.exceptions import OptimisticConcurrencyConflictException
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
    CaseQueueStatus,
    DatasetStatus,
    EvaluationDimension,
    OnlineSampleStatus,
    OutboxStatus,
    RunStatus,
)
from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import CandidateId, DatasetId, IdempotencyKey, ReportId, RunId, TestCaseId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate
from evaluationimprovement.domain.regression_report import RegressionReport
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.test_case import EvaluationTestCase


class InMemoryDatasetRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[DatasetId, EvaluationDataset] = {}

    def find_by_id(self, dataset_id: DatasetId) -> EvaluationDataset | None:
        return self._by_id.get(dataset_id)

    def find_by_name_version(self, name: str, version: str) -> EvaluationDataset | None:
        with self._lock:
            for dataset in self._by_id.values():
                if dataset.name == name and dataset.version == version:
                    return dataset
        return None

    def save(self, dataset: EvaluationDataset, expected_status: DatasetStatus | None) -> EvaluationDataset:
        with self._lock:
            existing = self._by_id.get(dataset.dataset_id)
            if expected_status is None:
                if existing is not None:
                    raise OptimisticConcurrencyConflictException(f"dataset {dataset.dataset_id}")
            else:
                if existing is None or existing.status != expected_status:
                    raise OptimisticConcurrencyConflictException(f"dataset {dataset.dataset_id}")
            self._by_id[dataset.dataset_id] = dataset
            return dataset

    def list_published(self, domain: str | None, tenant_id: str, limit: int) -> list[EvaluationDataset]:
        with self._lock:
            values = [d for d in self._by_id.values() if d.status == DatasetStatus.PUBLISHED and d.tenant_id == tenant_id]
        if domain is not None:
            values = [d for d in values if d.domain == domain]
        return sorted(values, key=lambda d: d.created_at, reverse=True)[:limit]

    def find_versions(self, name: str, tenant_id: str) -> list[EvaluationDataset]:
        with self._lock:
            values = [d for d in self._by_id.values() if d.name == name and d.tenant_id == tenant_id]
        return sorted(values, key=lambda d: d.created_at)


class InMemoryTestCaseRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[TestCaseId, EvaluationTestCase] = {}

    def find_by_id(self, test_case_id: TestCaseId) -> EvaluationTestCase | None:
        return self._by_id.get(test_case_id)

    def find_by_dataset(self, dataset_id: DatasetId) -> list[EvaluationTestCase]:
        with self._lock:
            return [c for c in self._by_id.values() if c.dataset_id == dataset_id]

    def find_by_natural_key(self, dataset_id: DatasetId, case_key: str) -> EvaluationTestCase | None:
        with self._lock:
            for case in self._by_id.values():
                if case.dataset_id == dataset_id and case.case_key == case_key:
                    return case
        return None

    def save_many(self, cases: tuple[EvaluationTestCase, ...]) -> None:
        with self._lock:
            for case in cases:
                self._by_id[case.test_case_id] = case


class InMemoryEvaluationRunRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[RunId, EvaluationRun] = {}
        self._generations: dict[RunId, int] = {}

    def find_by_id(self, run_id: RunId) -> EvaluationRun | None:
        return self._by_id.get(run_id)

    def find_by_run_key(self, run_key: str) -> EvaluationRun | None:
        with self._lock:
            for run in self._by_id.values():
                if run.run_key == run_key:
                    return run
        return None

    def save(self, run: EvaluationRun, expected_status: RunStatus | None) -> EvaluationRun:
        with self._lock:
            existing = self._by_id.get(run.run_id)
            if expected_status is None:
                if existing is not None:
                    raise OptimisticConcurrencyConflictException(f"evaluation run {run.run_id}")
                self._generations[run.run_id] = 1
            else:
                if existing is None or existing.status != expected_status:
                    raise OptimisticConcurrencyConflictException(f"evaluation run {run.run_id}")
            self._by_id[run.run_id] = run
            return run

    def current_generation(self, run_id: RunId) -> int:
        return self._generations.get(run_id, 0)

    def find_by_dataset(self, dataset_id: DatasetId, status: RunStatus | None, limit: int) -> list[EvaluationRun]:
        with self._lock:
            values = [r for r in self._by_id.values() if r.dataset_id == dataset_id]
        if status is not None:
            values = [r for r in values if r.status == status]
        return sorted(values, key=lambda r: r.started_at, reverse=True)[:limit]

    def find_stuck(self, statuses: frozenset[RunStatus], older_than: datetime) -> list[EvaluationRun]:
        with self._lock:
            values = [r for r in self._by_id.values() if r.status in statuses and r.started_at < older_than]
        return sorted(values, key=lambda r: r.started_at)


class InMemoryScoreRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._scores: list[EvaluationScore] = []

    def save(self, score: EvaluationScore) -> EvaluationScore:
        with self._lock:
            self._save_locked(score)
            return score

    def save_many(self, scores: tuple[EvaluationScore, ...]) -> tuple[EvaluationScore, ...]:
        with self._lock:
            for score in scores:
                self._save_locked(score)
            return scores

    def _save_locked(self, score: EvaluationScore) -> None:
        for i, existing in enumerate(self._scores):
            if (
                existing.is_active and existing.run_id == score.run_id and existing.test_case_id == score.test_case_id
                and existing.dimension == score.dimension
            ):
                self._scores[i] = existing.superseded()
        self._scores.append(score)

    def find_active_by_run(self, run_id: RunId) -> list[EvaluationScore]:
        with self._lock:
            return [s for s in self._scores if s.is_active and s.run_id == run_id]

    def find_active(self, run_id: RunId, test_case_id: TestCaseId, dimension: EvaluationDimension) -> EvaluationScore | None:
        for s in self.find_active_by_run(run_id):
            if s.test_case_id == test_case_id and s.dimension == dimension:
                return s
        return None

    def count_distinct_scored_cases(self, run_id: RunId) -> int:
        return len({s.test_case_id for s in self.find_active_by_run(run_id)})


class InMemoryCaseExecutionResultRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._results: dict[tuple[str, str], CaseExecutionResult] = {}

    def save(self, result: CaseExecutionResult) -> None:
        with self._lock:
            self._results[(result.run_id, result.test_case_id)] = result

    def find(self, run_id: RunId, test_case_id: TestCaseId) -> CaseExecutionResult | None:
        return self._results.get((str(run_id), str(test_case_id)))

    def find_by_run(self, run_id: RunId) -> list[CaseExecutionResult]:
        with self._lock:
            return [r for r in self._results.values() if r.run_id == str(run_id)]


class InMemoryCaseExecutionQueueRepository:
    """SPEC-EI-011. Keyed by (run_id, test_case_id), mirroring
    InMemoryCaseExecutionResultRepository's own dict key exactly.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: dict[tuple[str, str], CaseExecutionLease] = {}

    def enqueue_many(self, run_id: RunId, test_case_ids: tuple[TestCaseId, ...], run_generation: int, now: datetime) -> None:
        with self._lock:
            for test_case_id in test_case_ids:
                key = (str(run_id), str(test_case_id))
                if key in self._entries:
                    continue
                self._entries[key] = CaseExecutionLease(
                    run_id=str(run_id), test_case_id=str(test_case_id), run_generation=run_generation,
                    status=CaseQueueStatus.PENDING, attempt_count=0, next_attempt_at=now,
                )

    def find_claimable(self, now: datetime, limit: int) -> list[CaseExecutionLease]:
        with self._lock:
            due = [e for e in self._entries.values() if e.status == CaseQueueStatus.PENDING and e.next_attempt_at <= now]
        return sorted(due, key=lambda e: e.next_attempt_at)[:limit]

    def claim(self, run_id: RunId, test_case_id: TestCaseId, worker_id: str, now: datetime, lease_expires_at: datetime) -> bool:
        key = (str(run_id), str(test_case_id))
        with self._lock:
            entry = self._entries.get(key)
            if entry is None or entry.status != CaseQueueStatus.PENDING:
                return False
            self._entries[key] = dataclasses.replace(
                entry, status=CaseQueueStatus.LEASED, leased_by=worker_id, leased_at=now, lease_expires_at=lease_expires_at,
            )
            return True

    def mark_done(self, run_id: RunId, test_case_id: TestCaseId) -> None:
        self._replace_status((str(run_id), str(test_case_id)), CaseQueueStatus.DONE)

    def mark_retry(self, run_id: RunId, test_case_id: TestCaseId, next_attempt_at: datetime, attempt_count: int) -> None:
        key = (str(run_id), str(test_case_id))
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return
            self._entries[key] = dataclasses.replace(
                entry, status=CaseQueueStatus.PENDING, attempt_count=attempt_count, next_attempt_at=next_attempt_at,
                leased_by=None, leased_at=None, lease_expires_at=None,
            )

    def mark_exhausted(self, run_id: RunId, test_case_id: TestCaseId, attempt_count: int) -> None:
        key = (str(run_id), str(test_case_id))
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return
            self._entries[key] = dataclasses.replace(entry, status=CaseQueueStatus.EXHAUSTED, attempt_count=attempt_count)

    def find_expired_leases(self, now: datetime, limit: int) -> list[CaseExecutionLease]:
        with self._lock:
            expired = [
                e for e in self._entries.values()
                if e.status == CaseQueueStatus.LEASED and e.lease_expires_at is not None and e.lease_expires_at < now
            ]
        return sorted(expired, key=lambda e: e.lease_expires_at)[:limit]  # type: ignore[arg-type, return-value]

    def release_expired_lease(self, run_id: RunId, test_case_id: TestCaseId, next_attempt_at: datetime, attempt_count: int) -> bool:
        key = (str(run_id), str(test_case_id))
        with self._lock:
            entry = self._entries.get(key)
            if entry is None or entry.status != CaseQueueStatus.LEASED:
                return False
            self._entries[key] = dataclasses.replace(
                entry, status=CaseQueueStatus.PENDING, attempt_count=attempt_count, next_attempt_at=next_attempt_at,
                leased_by=None, leased_at=None, lease_expires_at=None,
            )
            return True

    def find_by_run(self, run_id: RunId) -> list[CaseExecutionLease]:
        with self._lock:
            return [e for e in self._entries.values() if e.run_id == str(run_id)]

    def _replace_status(self, key: tuple[str, str], status: CaseQueueStatus) -> None:
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return
            self._entries[key] = dataclasses.replace(entry, status=status)


class InMemoryLangSmithLinkRepository:
    """SPEC-EI-013. One record per run — a later save() for the same run_id replaces
    the prior one (a run only ever calls LangSmithPort.link_experiment() once, at
    create_run() time, but this stays a replace rather than an insert-only dict write
    to match every other single-key repository in this module).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_run_id: dict[str, LangSmithLinkRecord] = {}

    def save(self, record: LangSmithLinkRecord) -> None:
        with self._lock:
            self._by_run_id[record.run_id] = record

    def find(self, run_id: RunId) -> LangSmithLinkRecord | None:
        return self._by_run_id.get(str(run_id))


class InMemoryJudgeBundleStatusRepository:
    """SPEC-EI-018. One record per `grader_version` — a later save() for the same
    version replaces the prior one (the latest calibration check is the only one that
    matters for gating).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_grader_version: dict[str, JudgeBundleStatus] = {}

    def find_status(self, grader_version: str) -> JudgeBundleStatus | None:
        return self._by_grader_version.get(grader_version)

    def save_status(self, status: JudgeBundleStatus) -> JudgeBundleStatus:
        with self._lock:
            self._by_grader_version[status.grader_version] = status
            return status


class InMemoryRegressionReportRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[ReportId, RegressionReport] = {}

    def save(self, report: RegressionReport) -> RegressionReport:
        with self._lock:
            self._by_id[report.report_id] = report
            return report

    def find_by_id(self, report_id: ReportId) -> RegressionReport | None:
        return self._by_id.get(report_id)

    def find_by_run(self, run_id: RunId) -> RegressionReport | None:
        with self._lock:
            for report in self._by_id.values():
                if report.run_id == run_id:
                    return report
        return None


class InMemoryImprovementCandidateRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_id: dict[CandidateId, ImprovementCandidate] = {}

    def find_by_id(self, candidate_id: CandidateId) -> ImprovementCandidate | None:
        return self._by_id.get(candidate_id)

    def save(self, candidate: ImprovementCandidate, expected_status: CandidateStatus | None) -> ImprovementCandidate:
        with self._lock:
            existing = self._by_id.get(candidate.candidate_id)
            if expected_status is None:
                if existing is not None:
                    raise OptimisticConcurrencyConflictException(f"improvement candidate {candidate.candidate_id}")
            else:
                if existing is None or existing.status != expected_status:
                    raise OptimisticConcurrencyConflictException(f"improvement candidate {candidate.candidate_id}")
            self._by_id[candidate.candidate_id] = candidate
            return candidate

    def find_by_natural_key(
        self, source_run_id: RunId, source_failure_cluster_id: str | None, target_component: str
    ) -> ImprovementCandidate | None:
        with self._lock:
            for candidate in self._by_id.values():
                if (
                    candidate.source_run_id == source_run_id
                    and candidate.source_failure_cluster_id == source_failure_cluster_id
                    and candidate.target_component == target_component
                ):
                    return candidate
        return None

    def find_by_approval_request_id(self, approval_request_id: str) -> ImprovementCandidate | None:
        with self._lock:
            for candidate in self._by_id.values():
                if candidate.approval_request_id == approval_request_id:
                    return candidate
        return None


class InMemoryOutboxRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._records: dict[uuid.UUID, OutboxRecord] = {}

    def append(self, record: OutboxRecord) -> None:
        with self._lock:
            self._records[record.outbox_id] = record

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        # FAILED is a retry-eligible state, not a terminal one — a row parked there by
        # mark_failed() must come back once its own backoff `available_at` has passed.
        # Only PUBLISHED/DEAD_LETTER are excluded from ever being picked up again.
        with self._lock:
            due = [
                r for r in self._records.values()
                if r.status in (OutboxStatus.PENDING, OutboxStatus.FAILED) and (r.available_at is None or r.available_at <= now)
            ]
        return sorted(due, key=lambda r: r.occurred_at)[:limit]

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = _replace_outbox(record, status=OutboxStatus.PUBLISHED, published_at=published_at)

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = _replace_outbox(
                record, status=OutboxStatus.FAILED, available_at=next_available_at, attempts=attempts,
            )

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None:
        with self._lock:
            record = self._records[outbox_id]
            self._records[outbox_id] = _replace_outbox(record, status=OutboxStatus.DEAD_LETTER)

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        with self._lock:
            values = [r for r in self._records.values() if r.status == OutboxStatus.DEAD_LETTER]
        return sorted(values, key=lambda r: r.occurred_at)[:limit]


def _replace_outbox(record: OutboxRecord, **changes: object) -> OutboxRecord:
    return dataclasses.replace(record, **changes)


class InMemoryProcessedEventRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._processed: set[tuple[str, str]] = set()

    def is_processed(self, event_id: str, consumer_name: str) -> bool:
        with self._lock:
            return (event_id, consumer_name) in self._processed

    def mark_processed(self, record: ProcessedEventRecord) -> None:
        with self._lock:
            self._processed.add((record.event_id, record.consumer_name))


class InMemoryPoisonEventRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: list[PoisonEventRecord] = []

    def record(self, entry: PoisonEventRecord) -> PoisonEventRecord:
        with self._lock:
            self._entries.append(entry)
            return entry

    def find_all(self, limit: int) -> list[PoisonEventRecord]:
        with self._lock:
            return list(reversed(self._entries))[:limit]


class InMemoryCommandIdempotencyRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_key: dict[IdempotencyKey, CommandIdempotencyRecord] = {}

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None:
        return self._by_key.get(idempotency_key)

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord:
        with self._lock:
            self._by_key[record.idempotency_key] = record
            return record


class InMemoryAuditRecordRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: list[AuditRecordEntry] = []

    def append(self, entry: AuditRecordEntry) -> None:
        with self._lock:
            self._entries.append(entry)

    def find_recent(self, limit: int) -> list[AuditRecordEntry]:
        with self._lock:
            return list(reversed(self._entries))[:limit]


_DEFAULT_GATE_POLICY = GatePolicyConfig(
    gate_policy="mvp-release-gate-v1",
    dimension_thresholds={"CLASSIFICATION_ACCURACY": 0.9, "TOOL_SELECTION": 0.95},
    critical_case_required=True, max_policy_violations=0, max_forbidden_tool_calls=0, max_unauthorized_memory_access=0,
)


class InMemoryGatePolicyRepository:
    """Seeded with `mvp-release-gate-v1` (05-api-contracts sample create-run request
    names this exact gate policy id) so a fresh service instance always has at least
    one usable gate policy.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._policies: dict[str, GatePolicyConfig] = {_DEFAULT_GATE_POLICY.gate_policy: _DEFAULT_GATE_POLICY}

    def find_by_name(self, gate_policy: str) -> GatePolicyConfig | None:
        return self._policies.get(gate_policy)

    def save(self, config: GatePolicyConfig) -> GatePolicyConfig:
        with self._lock:
            self._policies[config.gate_policy] = config
            return config


class InMemoryOnlineSampleRepository:
    """SPEC-EI-028. Keyed by sample_id, mirroring InMemoryJudgeBundleStatusRepository's
    own single-dict shape.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._samples: dict[uuid.UUID, OnlineEvaluationSample] = {}

    def save(self, sample: OnlineEvaluationSample) -> OnlineEvaluationSample:
        with self._lock:
            self._samples[sample.sample_id] = sample
            return sample

    def find_by_id(self, sample_id: uuid.UUID) -> OnlineEvaluationSample | None:
        return self._samples.get(sample_id)

    def find_queued(self, limit: int) -> list[OnlineEvaluationSample]:
        with self._lock:
            queued = [s for s in self._samples.values() if s.status == OnlineSampleStatus.QUEUED]
            return sorted(queued, key=lambda s: s.collected_at)[:limit]

    def find_by_candidate(self, candidate_id: CandidateId) -> list[OnlineEvaluationSample]:
        with self._lock:
            return [s for s in self._samples.values() if s.candidate_id == candidate_id.value]
