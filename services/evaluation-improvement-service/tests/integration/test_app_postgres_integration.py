"""SPEC-EI-002 acceptance-criteria: "business state + audit + outbox 同事务" and the
full create_dataset -> publish_dataset -> create_run -> execute_case -> score_case ->
finalize_scoring -> compare_regression -> evaluate_release_gate pipeline, driven end
to end through a real evaluationimprovement.container.Container built against the
real, migrated Postgres schema rather than SPEC-EI-001's own in-memory adapters.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CompareRegressionCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    EvaluateReleaseGateCommand,
    ExecuteCaseCommand,
    FinalizeRunScoringCommand,
    PublishDatasetCommand,
    ScoreCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality

pytestmark = pytest.mark.integration


@pytest.mark.integration
def test_full_pipeline_against_real_postgres(postgres_settings, migrated_engine) -> None:  # noqa: ARG001 (migrated_engine ensures schema exists first)
    container = Container(postgres_settings)

    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=("mfa",),
        created_by="author-1", actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="duo-enrollment-expired", scenario="Duo enrollment expired", user_request_redacted="mfa broken",
        mock_system_state={"duoStatus": "EXPIRED"}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=("disable_mfa",), required_approval=False,
        verification_condition={"duoStatus": "ACTIVE"}, criticality=Criticality.CRITICAL,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    assert published.status.value == "PUBLISHED"

    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="pg-e2e-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))

    # 08-transaction-and-outbox: "创建 run 时，evaluation_runs 与
    # evaluation.run.requested.v1 outbox 必须同事务提交" — the outbox row must already
    # be visible via the real (still in-memory, SPEC-EI-003 scope) OutboxRepository
    # even though evaluation_runs itself is now real Postgres.
    pending = container.outbox_repository.find_dispatchable(container.clock.now(), 10)
    assert any(r.event_type == "evaluation.run.requested.v1" and r.aggregate_id == str(run.run_id) for r in pending)

    container.execute_case_service.execute_case(ExecuteCaseCommand(
        run_id=run.run_id, test_case_id=added[0].test_case_id, attempt=1, actor="ci", correlation_id="corr-1",
    ))
    scores = container.score_run_service.score_case(ScoreCaseCommand(
        run_id=run.run_id, test_case_id=added[0].test_case_id, run_generation=1, actor="ci", correlation_id="corr-1",
    ))
    assert all(s.passed for s in scores)

    comparing = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    assert comparing.status.value == "COMPARING"

    report = container.compare_regression_service.compare(CompareRegressionCommand(run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1"))
    assert report.overall_decision.value == "PASSED"

    final_report = container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(run_id=run.run_id, gate_policy="mvp-release-gate-v1", actor="ci", correlation_id="corr-1"))
    assert final_report.overall_decision.value == "PASSED"

    final_run = container.create_run_service.find_run(run.run_id)
    assert final_run.status.value == "PASSED"


@pytest.mark.integration
def test_dataset_survives_a_fresh_repository_instance(postgres_settings, migrated_engine) -> None:  # noqa: ARG001
    """Proves durability, not just round-trip fidelity within one Container instance
    — a genuinely new Container (a fresh SQLAlchemy engine/session factory) can still
    read what an earlier one wrote.
    """
    first_container = Container(postgres_settings)
    dataset = first_container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="durable-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))

    second_container = Container(postgres_settings)
    found = second_container.create_dataset_service.find_dataset(dataset.dataset_id, "default")
    assert found.name == "durable-dataset"
