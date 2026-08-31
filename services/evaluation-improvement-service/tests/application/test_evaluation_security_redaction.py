"""SPEC-EI-034 (evaluation-security-redaction-observability) / 11-security
§"数据保护": "Report 默认展示聚合分数；case-level evidence 需要更高权限." —
CreateRunService.find_scores() is the one place that rule is actually enforced.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    ExecuteCaseCommand,
    PublishDatasetCommand,
    ScoreCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import RunId


def _drive_run_to_scored(container: Container, run_key: str) -> RunId:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"security-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="Duo enrollment expired", user_request_redacted="mfa broken", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=("reset_duo_enrollment",), forbidden_tools=(),
        required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(run_id=run.run_id, test_case_id=added[0].test_case_id, attempt=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.score_case(ScoreCaseCommand(run_id=run.run_id, test_case_id=added[0].test_case_id, run_generation=1, actor="ci", correlation_id="corr-1"))
    return run.run_id


@pytest.mark.unit
def test_a_viewer_sees_aggregate_scores_without_evidence(container: Container) -> None:
    run_id = _drive_run_to_scored(container, "sec-viewer-001")
    scores = container.create_run_service.find_scores(run_id, "viewer-1", "EVALUATION_VIEWER")
    assert scores
    for s in scores:
        assert s.evidence_ref is None
        assert s.details == {}
        # Aggregate fields are never hidden.
        assert s.score is not None
        assert s.dimension is not None


@pytest.mark.unit
def test_an_admin_sees_evidence_and_the_access_is_audited(container: Container) -> None:
    run_id = _drive_run_to_scored(container, "sec-admin-001")
    before = len(container.audit_record_query_service.list_audit_events(1000))

    scores = container.create_run_service.find_scores(run_id, "admin-1", "EVALUATION_ADMIN")
    assert scores
    assert any(s.evidence_ref is not None for s in scores)

    after = container.audit_record_query_service.list_audit_events(1000)
    assert len(after) == before + 1
    entry = after[0]
    assert entry.action == "view_sensitive_evidence"
    assert entry.resource_id == str(run_id)
    assert entry.actor == "admin-1"


@pytest.mark.unit
def test_a_viewers_read_is_never_audited(container: Container) -> None:
    run_id = _drive_run_to_scored(container, "sec-viewer-002")
    before = len(container.audit_record_query_service.list_audit_events(1000))
    container.create_run_service.find_scores(run_id, "viewer-1", "EVALUATION_VIEWER")
    after = container.audit_record_query_service.list_audit_events(1000)
    assert len(after) == before
