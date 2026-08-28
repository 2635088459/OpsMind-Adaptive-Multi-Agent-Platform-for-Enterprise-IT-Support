"""SPEC-EI-002 test-plan §"Integration Tests": "PostgreSQL migration、唯一键、索引和
JSONB 字段." Exercises each Postgres-backed repository directly against the real,
migrated schema — unique-constraint conflicts, optimistic-concurrency CAS, and
JSONB round-trip fidelity.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from evaluationimprovement.application.exceptions import DatasetVersionConflictException, OptimisticConcurrencyConflictException
from evaluationimprovement.application.records import CaseExecutionResult, GatePolicyConfig
from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.enums import CaseExecutionStatus, Criticality, DatasetStatus, EvaluationDimension, GraderType, RunStatus
from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import DatasetId, RunId, ScoreId, TestCaseId
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.domain.values import EvidenceRef, VersionBinding
from evaluationimprovement.infrastructure.persistence.postgres.repositories import (
    PostgresCaseExecutionResultRepository,
    PostgresDatasetRepository,
    PostgresEvaluationRunRepository,
    PostgresGatePolicyRepository,
    PostgresScoreRepository,
    PostgresTestCaseRepository,
)

pytestmark = pytest.mark.integration

_NOW = datetime.now(UTC)


@pytest.mark.integration
def test_dataset_round_trip_and_name_version_uniqueness(session_factory) -> None:
    repo = PostgresDatasetRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "identity-mfa-golden", "2026.08.1", "IDENTITY_ACCESS", ("mfa",), "author-1", _NOW)
    saved = repo.save(dataset, expected_status=None)
    assert saved.dataset_id == dataset.dataset_id

    found = repo.find_by_id(dataset.dataset_id)
    assert found is not None
    assert found.name == "identity-mfa-golden"
    assert found.scenario_tags == ("mfa",)
    assert found.status is DatasetStatus.DRAFT

    duplicate = EvaluationDataset.create(DatasetId.new_id(), "identity-mfa-golden", "2026.08.1", "IDENTITY_ACCESS", (), "author-2", _NOW)
    with pytest.raises(DatasetVersionConflictException):
        repo.save(duplicate, expected_status=None)


@pytest.mark.integration
def test_find_versions_returns_the_lineage_chain_oldest_first(session_factory) -> None:
    repo = PostgresDatasetRepository(session_factory)
    first = EvaluationDataset.create(DatasetId.new_id(), "lineage-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    repo.save(first, expected_status=None)
    second = EvaluationDataset.create(
        DatasetId.new_id(), "lineage-dataset", "2", "IDENTITY_ACCESS", (), "author-1", _NOW + timedelta(seconds=1),
        lineage_parent_id=first.dataset_id,
    )
    repo.save(second, expected_status=None)
    # A differently-named dataset must never show up in this lineage chain.
    repo.save(EvaluationDataset.create(DatasetId.new_id(), "unrelated-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW), expected_status=None)

    versions = repo.find_versions("lineage-dataset", "default")
    assert [v.dataset_id for v in versions] == [first.dataset_id, second.dataset_id]
    assert versions[1].lineage_parent_id == first.dataset_id


@pytest.mark.integration
def test_dataset_cas_rejects_a_stale_expected_status(session_factory) -> None:
    repo = PostgresDatasetRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "cas-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    repo.save(dataset, expected_status=None)

    with pytest.raises(OptimisticConcurrencyConflictException):
        # Real status is DRAFT, not REVIEWING — the CAS predicate must not match.
        repo.save(dataset.start_review(), expected_status=DatasetStatus.REVIEWING)

    # The correct expected_status succeeds.
    reviewing = repo.save(dataset.start_review(), expected_status=DatasetStatus.DRAFT)
    assert reviewing.status is DatasetStatus.REVIEWING


@pytest.mark.integration
def test_test_case_natural_key_lookup_and_jsonb_round_trip(session_factory) -> None:
    dataset_repo = PostgresDatasetRepository(session_factory)
    case_repo = PostgresTestCaseRepository(session_factory)

    dataset = EvaluationDataset.create(DatasetId.new_id(), "case-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(dataset, expected_status=None)

    case = EvaluationTestCase.create(
        TestCaseId.new_id(), dataset.dataset_id, "duo-enrollment-expired", "Duo enrollment expired", "mfa broken",
        {"duoStatus": "EXPIRED"}, {"classification": "MFA_ENROLLMENT_EXPIRED"}, ("reset_duo_enrollment",), ("disable_mfa",),
        False, {"duoStatus": "ACTIVE"}, Criticality.CRITICAL,
    )
    case_repo.save_many((case,))

    found = case_repo.find_by_natural_key(dataset.dataset_id, "duo-enrollment-expired")
    assert found is not None
    assert found.ground_truth == {"classification": "MFA_ENROLLMENT_EXPIRED"}
    assert found.allowed_tools == ("reset_duo_enrollment",)
    assert found.forbidden_tools == ("disable_mfa",)
    assert found.mock_system_state == {"duoStatus": "EXPIRED"}
    assert found.input_hash == case.input_hash

    assert len(case_repo.find_by_dataset(dataset.dataset_id)) == 1


@pytest.mark.integration
def test_score_supersede_keeps_exactly_one_active_row(session_factory) -> None:
    # evaluation_scores.run_id/test_case_id are real foreign keys — a score can only
    # ever be saved against an actual run/test case, so this seeds both first.
    dataset_repo = PostgresDatasetRepository(session_factory)
    case_repo = PostgresTestCaseRepository(session_factory)
    run_repo = PostgresEvaluationRunRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "score-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(dataset, expected_status=None)
    case = EvaluationTestCase.create(
        TestCaseId.new_id(), dataset.dataset_id, "k1", "s", "", {}, {"classification": "X"}, (), (), False, {}, Criticality.STANDARD,
    )
    case_repo.save_many((case,))
    binding = VersionBinding("1", "target:rc1", "grader:v1", "policy:v1", "corr-1")
    run = EvaluationRun.create(RunId.new_id(), "score-run-001", dataset.dataset_id, binding, "ci", _NOW)
    run_repo.save(run, expected_status=None)

    repo = PostgresScoreRepository(session_factory)
    run_id, test_case_id = run.run_id, case.test_case_id

    first = EvaluationScore.create(
        ScoreId.new_id(), run_id, test_case_id, EvaluationDimension.CLASSIFICATION_ACCURACY, 0.5, 1.0, GraderType.DETERMINISTIC, "v1",
        evidence_ref=EvidenceRef("agent-runtime", "trace://1", "hash1"),
    )
    repo.save(first)
    second = EvaluationScore.create(
        ScoreId.new_id(), run_id, test_case_id, EvaluationDimension.CLASSIFICATION_ACCURACY, 1.0, 1.0, GraderType.DETERMINISTIC, "v1",
    )
    repo.save(second)

    active = repo.find_active_by_run(run_id)
    assert len(active) == 1
    assert active[0].score_id == second.score_id
    assert active[0].score == 1.0
    assert repo.count_distinct_scored_cases(run_id) == 1

    active_score = repo.find_active(run_id, test_case_id, EvaluationDimension.CLASSIFICATION_ACCURACY)
    assert active_score is not None
    assert active_score.score_id == second.score_id
    assert active_score.evidence_ref is None  # the superseded first row carried evidence; the active one does not


@pytest.mark.integration
def test_gate_policy_upsert(session_factory) -> None:
    repo = PostgresGatePolicyRepository(session_factory)
    repo.save(GatePolicyConfig(gate_policy="ci-gate", dimension_thresholds={"CLASSIFICATION_ACCURACY": 0.8}))
    found = repo.find_by_name("ci-gate")
    assert found is not None
    assert found.dimension_thresholds == {"CLASSIFICATION_ACCURACY": 0.8}

    repo.save(GatePolicyConfig(gate_policy="ci-gate", dimension_thresholds={"CLASSIFICATION_ACCURACY": 0.95}, critical_case_required=False))
    updated = repo.find_by_name("ci-gate")
    assert updated is not None
    assert updated.dimension_thresholds == {"CLASSIFICATION_ACCURACY": 0.95}
    assert updated.critical_case_required is False


@pytest.mark.integration
def test_case_execution_result_upsert_by_natural_key(session_factory) -> None:
    dataset_repo = PostgresDatasetRepository(session_factory)
    case_repo = PostgresTestCaseRepository(session_factory)

    dataset = EvaluationDataset.create(DatasetId.new_id(), "exec-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(dataset, expected_status=None)
    case = EvaluationTestCase.create(
        TestCaseId.new_id(), dataset.dataset_id, "k1", "s", "", {}, {"classification": "X"}, (), (), False, {}, Criticality.STANDARD,
    )
    case_repo.save_many((case,))
    run_repo = PostgresEvaluationRunRepository(session_factory)
    binding = VersionBinding("1", "target:rc1", "grader:v1", "policy:v1", "corr-1")
    run = EvaluationRun.create(RunId.new_id(), "exec-run-001", dataset.dataset_id, binding, "ci", _NOW)
    run_repo.save(run, expected_status=None)

    result_repo = PostgresCaseExecutionResultRepository(session_factory)
    result_repo.save(CaseExecutionResult(
        run_id=str(run.run_id), test_case_id=str(case.test_case_id), run_generation=1, final_state="RESOLVED",
        tool_calls=("reset_duo_enrollment",), classification="X", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=100, latency_ms=500, workflow_trace_ref="trace-1",
    ))
    found = result_repo.find(run.run_id, case.test_case_id)
    assert found is not None
    assert found.workflow_trace_ref == "trace-1"

    # Re-executing the same case (a retry) upserts in place rather than appending.
    result_repo.save(CaseExecutionResult(
        run_id=str(run.run_id), test_case_id=str(case.test_case_id), run_generation=1, final_state="RESOLVED",
        tool_calls=("reset_duo_enrollment",), classification="X", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=200, latency_ms=800, workflow_trace_ref="trace-2",
    ))
    refreshed = result_repo.find(run.run_id, case.test_case_id)
    assert refreshed is not None
    assert refreshed.workflow_trace_ref == "trace-2"
    assert len(result_repo.find_by_run(run.run_id)) == 1


@pytest.mark.integration
def test_case_execution_result_status_and_failure_reason_round_trip(session_factory) -> None:
    """SPEC-EI-009: a FAILED result (with its own failure_reason) survives a real
    Postgres round trip, replacing the earlier `completed` boolean column.
    """
    dataset_repo = PostgresDatasetRepository(session_factory)
    case_repo = PostgresTestCaseRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "exec-status-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(dataset, expected_status=None)
    case = EvaluationTestCase.create(
        TestCaseId.new_id(), dataset.dataset_id, "k1", "s", "", {}, {"classification": "X"}, (), (), False, {}, Criticality.STANDARD,
    )
    case_repo.save_many((case,))
    run_repo = PostgresEvaluationRunRepository(session_factory)
    binding = VersionBinding("1", "target:rc1", "grader:v1", "policy:v1", "corr-1")
    run = EvaluationRun.create(RunId.new_id(), "exec-status-run-001", dataset.dataset_id, binding, "ci", _NOW)
    run_repo.save(run, expected_status=None)

    result_repo = PostgresCaseExecutionResultRepository(session_factory)
    result_repo.save(CaseExecutionResult(
        run_id=str(run.run_id), test_case_id=str(case.test_case_id), run_generation=1, final_state="RESOLVED",
        tool_calls=(), classification="X", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=100, latency_ms=500, workflow_trace_ref="trace-1",
    ))
    found = result_repo.find(run.run_id, case.test_case_id)
    assert found is not None
    assert found.status == CaseExecutionStatus.COMPLETED
    assert found.failure_reason is None

    result_repo.save(CaseExecutionResult(
        run_id=str(run.run_id), test_case_id=str(case.test_case_id), run_generation=1, final_state="", tool_calls=(),
        classification="", policy_violation_count=0, forbidden_tool_call_count=0, unauthorized_memory_access_count=0,
        cost_tokens=0, latency_ms=0, workflow_trace_ref="", status=CaseExecutionStatus.FAILED, failure_reason="agent runtime timed out",
    ))
    refreshed = result_repo.find(run.run_id, case.test_case_id)
    assert refreshed is not None
    assert refreshed.status == CaseExecutionStatus.FAILED
    assert refreshed.failure_reason == "agent runtime timed out"


@pytest.mark.integration
def test_dataset_content_hash_round_trips_through_postgres(session_factory) -> None:
    """SPEC-EI-007: content_hash is NULL until publish() sets it, and survives a real
    Postgres round trip afterward."""
    repo = PostgresDatasetRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "hash-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    repo.save(dataset, expected_status=None)
    found = repo.find_by_id(dataset.dataset_id)
    assert found is not None
    assert found.content_hash is None

    reviewing = dataset.with_case_count(1).start_review()
    repo.save(reviewing, expected_status=DatasetStatus.DRAFT)
    published = reviewing.publish("reviewer-1", _NOW, content_hash="deadbeef" * 8)
    repo.save(published, expected_status=DatasetStatus.REVIEWING)

    refreshed = repo.find_by_id(dataset.dataset_id)
    assert refreshed is not None
    assert refreshed.content_hash == "deadbeef" * 8


@pytest.mark.integration
def test_dataset_tenant_id_round_trips_and_scopes_list_and_versions(session_factory) -> None:
    """SPEC-EI-008 / 11-security: `tenant_id` survives a real Postgres round trip, and
    both `list_published`/`find_versions` genuinely filter by it at the SQL level —
    not just in a Python-side post-filter.
    """
    repo = PostgresDatasetRepository(session_factory)
    tenant_a = EvaluationDataset.create(
        DatasetId.new_id(), "tenant-a-pg-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW, tenant_id="tenant-a",
    ).with_case_count(1)
    repo.save(tenant_a, expected_status=None)
    reviewing_a = tenant_a.start_review()
    repo.save(reviewing_a, expected_status=DatasetStatus.DRAFT)
    published_a = reviewing_a.publish("reviewer-1", _NOW)
    repo.save(published_a, expected_status=DatasetStatus.REVIEWING)

    tenant_b = EvaluationDataset.create(
        DatasetId.new_id(), "tenant-b-pg-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW, tenant_id="tenant-b",
    ).with_case_count(1)
    repo.save(tenant_b, expected_status=None)
    reviewing_b = tenant_b.start_review()
    repo.save(reviewing_b, expected_status=DatasetStatus.DRAFT)
    published_b = reviewing_b.publish("reviewer-1", _NOW)
    repo.save(published_b, expected_status=DatasetStatus.REVIEWING)

    found = repo.find_by_id(published_a.dataset_id)
    assert found is not None
    assert found.tenant_id == "tenant-a"

    tenant_a_names = {d.name for d in repo.list_published(None, "tenant-a", 50)}
    tenant_b_names = {d.name for d in repo.list_published(None, "tenant-b", 50)}
    assert tenant_a_names == {"tenant-a-pg-dataset"}
    assert tenant_b_names == {"tenant-b-pg-dataset"}

    assert repo.find_versions("tenant-b-pg-dataset", "tenant-a") == []
    assert len(repo.find_versions("tenant-b-pg-dataset", "tenant-b")) == 1


@pytest.mark.integration
def test_run_find_by_dataset_scopes_by_dataset_and_status(session_factory) -> None:
    """SPEC-EI-010 / 05-api-contracts: "状态可见性" over a real Postgres schema — every
    run against a dataset, optionally narrowed to one status, never leaking a run
    that belongs to a different dataset.
    """
    dataset_repo = PostgresDatasetRepository(session_factory)
    run_repo = PostgresEvaluationRunRepository(session_factory)
    dataset = EvaluationDataset.create(DatasetId.new_id(), "run-visibility-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(dataset, expected_status=None)
    other_dataset = EvaluationDataset.create(DatasetId.new_id(), "unrelated-run-dataset", "1", "IDENTITY_ACCESS", (), "author-1", _NOW)
    dataset_repo.save(other_dataset, expected_status=None)

    binding = VersionBinding("1", "target:rc1", "grader:v1", "policy:v1", "corr-1")
    queued_run = EvaluationRun.create(RunId.new_id(), "visibility-run-queued", dataset.dataset_id, binding, "ci", _NOW)
    run_repo.save(queued_run, expected_status=None)
    cancelled_run = EvaluationRun.create(RunId.new_id(), "visibility-run-cancelled", dataset.dataset_id, binding, "ci", _NOW + timedelta(seconds=1))
    run_repo.save(cancelled_run, expected_status=None)
    run_repo.save(cancelled_run.cancel(_NOW), expected_status=RunStatus.QUEUED)
    # A run against a different dataset must never show up here.
    run_repo.save(EvaluationRun.create(RunId.new_id(), "unrelated-run", other_dataset.dataset_id, binding, "ci", _NOW), expected_status=None)

    all_runs = run_repo.find_by_dataset(dataset.dataset_id, None, 50)
    assert {r.run_id for r in all_runs} == {queued_run.run_id, cancelled_run.run_id}
    assert all_runs[0].run_id == cancelled_run.run_id  # newest first

    cancelled_only = run_repo.find_by_dataset(dataset.dataset_id, RunStatus.CANCELLED, 50)
    assert [r.run_id for r in cancelled_only] == [cancelled_run.run_id]
