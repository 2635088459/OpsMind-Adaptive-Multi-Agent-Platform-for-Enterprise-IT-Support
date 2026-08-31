"""SPEC-EI-023 (failure-clustering-root-cause-taxonomy): ClusterRunFailuresService
groups a run's own failed scores into `(dimension, failure_code)` root-cause taxonomy
categories, derived at query time — no dedicated persisted aggregate.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    PublishDatasetCommand,
    RunCiGateCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import RunNotFoundException
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality, ScoreFailureCode
from evaluationimprovement.domain.ids import RunId


def _publish_dataset(container: Container, name: str, *, case_kwargs: dict | None = None):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=name, version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    defaults = dict(
        case_key="k1", scenario="Duo enrollment expired", user_request_redacted="mfa broken", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=("reset_duo_enrollment",),
        forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    if case_kwargs:
        defaults.update(case_kwargs)
    case = TestCaseInput(**defaults)
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    return container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_unknown_run_raises_not_found(container: Container) -> None:
    with pytest.raises(RunNotFoundException):
        container.cluster_run_failures_service.list_clusters(RunId.new_id())


@pytest.mark.unit
def test_a_passing_run_has_no_deterministic_failure_clusters(container: Container) -> None:
    """A run's release-gate decision (INV-EI-003) only ever reads DETERMINISTIC
    scores — HANDOFF_COMPLETENESS is graded LLM_JUDGE-only and the default fake judge
    always leaves it UNSCORED, so it still shows up as its own failure cluster on an
    otherwise-passing run. Mirrors test_evaluate_release_gate_service.py's own
    DETERMINISTIC-only filtering for exactly this reason.
    """
    dataset = _publish_dataset(container, "cluster-pass-dataset")
    outcome = container.ci_evaluation_gate_service.run_gate(RunCiGateCommand(
        run_key="cluster-pass-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    assert outcome.passed is True
    clusters = container.cluster_run_failures_service.list_clusters(outcome.run_id)
    deterministic_clusters = [c for c in clusters if c.failure_code != ScoreFailureCode.UNSCORED]
    assert deterministic_clusters == []


@pytest.mark.unit
def test_a_wrong_classification_produces_a_stable_taxonomy_cluster(container: Container) -> None:
    dataset = _publish_dataset(container, "cluster-fail-dataset", case_kwargs={
        "mock_system_state": {"simulatedClassification": "WRONG_ANSWER"},
    })
    outcome = container.ci_evaluation_gate_service.run_gate(RunCiGateCommand(
        run_key="cluster-fail-001", dataset_id=dataset.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    assert outcome.passed is False

    clusters = container.cluster_run_failures_service.list_clusters(outcome.run_id)
    assert len(clusters) >= 1
    classification_cluster = next(c for c in clusters if c.dimension == "CLASSIFICATION_ACCURACY")
    assert classification_cluster.cluster_id == "CLASSIFICATION_ACCURACY:THRESHOLD_NOT_MET"
    assert classification_cluster.failure_code == ScoreFailureCode.THRESHOLD_NOT_MET
    assert classification_cluster.case_count == 1
    assert classification_cluster.run_id == outcome.run_id

    # Deterministic and reproducible — a repeat call returns the exact same grouping.
    again = container.cluster_run_failures_service.list_clusters(outcome.run_id)
    assert again == clusters
