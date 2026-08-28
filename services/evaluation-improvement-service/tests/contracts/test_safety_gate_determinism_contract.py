"""02-business-invariants INV-EI-003: "安全相关指标必须使用 deterministic grader 判定；LLM
Judge 只能用于质量类辅助评分." This is checked by actually seeding a terrible LLM_JUDGE
score (UNSCORED, score=0.0) alongside a passing DETERMINISTIC score for the same case,
and asserting the LLM_JUDGE score never drags the release-gate outcome down —
14-testing-strategy §"Contract Tests"/"Security Tests": "forbidden tool 与 policy
violation 必须 gate failed" implies the converse must also hold: an LLM Judge failure
alone must never gate failed.
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
from evaluationimprovement.domain.enums import Criticality, EvaluationDimension, GraderType, ScoreFailureCode
from evaluationimprovement.domain.ids import ScoreId
from evaluationimprovement.domain.score import EvaluationScore


@pytest.mark.unit
def test_a_failing_llm_judge_score_never_fails_the_release_gate(container: Container) -> None:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))

    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="judge-contract-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1",
        baseline_version=None, grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1",
        triggered_by="ci", actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(run_id=run.run_id, test_case_id=added[0].test_case_id, attempt=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.score_case(ScoreCaseCommand(run_id=run.run_id, test_case_id=added[0].test_case_id, run_generation=1, actor="ci", correlation_id="corr-1"))

    # Seed a terrible, UNSCORED LLM_JUDGE score directly (bypassing the grader
    # registry, which never produces one for this dimension in SPEC-EI-001's own
    # scope) to prove the *contract*, not merely today's registry configuration.
    container.score_repository.save(EvaluationScore.create(
        ScoreId.new_id(), run.run_id, added[0].test_case_id, EvaluationDimension.HANDOFF_COMPLETENESS, 0.0, 1.0,
        GraderType.LLM_JUDGE, "explanation-quality-judge-placeholder-v0", failure_code=ScoreFailureCode.UNSCORED,
    ))

    container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    report = container.compare_regression_service.compare(CompareRegressionCommand(run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1"))
    assert report.overall_decision.value == "PASSED"

    final_report = container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(run_id=run.run_id, gate_policy="mvp-release-gate-v1", actor="ci", correlation_id="corr-1"))
    assert final_report.overall_decision.value == "PASSED"
    assert container.create_run_service.find_run(run.run_id).status.value == "PASSED"
