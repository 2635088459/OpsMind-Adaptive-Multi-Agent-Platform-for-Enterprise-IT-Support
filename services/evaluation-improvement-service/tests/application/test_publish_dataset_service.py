from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    ArchiveDatasetCommand,
    CreateDatasetCommand,
    DeprecateDatasetCommand,
    PublishDatasetCommand,
    RejectDatasetReviewCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import DatasetNotFoundException
from evaluationimprovement.application.services.publish_dataset import _compute_content_hash
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException
from evaluationimprovement.domain.test_case import EvaluationTestCase


def _draft_dataset_with_case(container: Container, name: str = "identity-mfa-golden"):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=name, version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "X"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={},
        criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    return dataset


def _published_dataset(container: Container):
    dataset = _draft_dataset_with_case(container)
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    return container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_deprecate_then_archive_happy_path(container: Container) -> None:
    published = _published_dataset(container)
    deprecated = container.publish_dataset_service.deprecate(DeprecateDatasetCommand(dataset_id=published.dataset_id, actor="admin-1", correlation_id="corr-1"))
    assert deprecated.status.value == "DEPRECATED"

    archived = container.publish_dataset_service.archive(ArchiveDatasetCommand(dataset_id=published.dataset_id, actor="admin-1", correlation_id="corr-1"))
    assert archived.status.value == "ARCHIVED"


@pytest.mark.unit
def test_cannot_archive_a_dataset_that_is_not_yet_deprecated(container: Container) -> None:
    published = _published_dataset(container)
    with pytest.raises(InvalidStateTransitionException):
        container.publish_dataset_service.archive(ArchiveDatasetCommand(dataset_id=published.dataset_id, actor="admin-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_cannot_deprecate_a_draft_dataset(container: Container) -> None:
    draft = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="draft-only", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1", actor="author-1",
        correlation_id="corr-1",
    ))
    with pytest.raises(InvalidStateTransitionException):
        container.publish_dataset_service.deprecate(DeprecateDatasetCommand(dataset_id=draft.dataset_id, actor="admin-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_deprecate_unknown_dataset_raises_not_found(container: Container) -> None:
    with pytest.raises(DatasetNotFoundException):
        container.publish_dataset_service.deprecate(DeprecateDatasetCommand(dataset_id=DatasetId.new_id(), actor="admin-1", correlation_id="corr-1"))


@pytest.mark.unit
def test_deprecate_is_audited(container: Container) -> None:
    published = _published_dataset(container)
    container.publish_dataset_service.deprecate(DeprecateDatasetCommand(dataset_id=published.dataset_id, actor="admin-1", correlation_id="corr-1"))
    recent = container.audit_record_repository.find_recent(10)
    assert any(e.action == "deprecate_dataset" and e.resource_id == str(published.dataset_id) for e in recent)


@pytest.mark.unit
def test_submit_for_review_then_publish(container: Container) -> None:
    """SPEC-EI-006 / 04-use-cases UC-EI-001 step 3-4: the dataset sits in REVIEWING
    for an actual review before it's published — a genuinely separate step from
    publish()'s own auto-elevate convenience.
    """
    draft = _draft_dataset_with_case(container)
    reviewing = container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=draft.dataset_id, actor="author-1", correlation_id="corr-1"))
    assert reviewing.status.value == "REVIEWING"

    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=draft.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    assert published.status.value == "PUBLISHED"


@pytest.mark.unit
def test_reject_review_sends_it_back_and_requires_resubmission(container: Container) -> None:
    draft = _draft_dataset_with_case(container)
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=draft.dataset_id, actor="author-1", correlation_id="corr-1"))

    rejected = container.publish_dataset_service.reject_review(RejectDatasetReviewCommand(
        dataset_id=draft.dataset_id, reason="missing wrong-group coverage", actor="reviewer-1", correlation_id="corr-1",
    ))
    assert rejected.status.value == "DRAFT"

    with pytest.raises(InvalidStateTransitionException):
        container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=draft.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))

    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=draft.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=draft.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    assert published.status.value == "PUBLISHED"


@pytest.mark.unit
def test_reject_review_records_the_reason_in_audit(container: Container) -> None:
    draft = _draft_dataset_with_case(container)
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=draft.dataset_id, actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.reject_review(RejectDatasetReviewCommand(
        dataset_id=draft.dataset_id, reason="ground truth incomplete", actor="reviewer-1", correlation_id="corr-1",
    ))
    recent = container.audit_record_repository.find_recent(10)
    entry = next(e for e in recent if e.action == "reject_dataset_review")
    assert "ground truth incomplete" in entry.detail


@pytest.mark.unit
def test_publish_freezes_a_real_content_hash(container: Container) -> None:
    """SPEC-EI-007 / 07-data-model §"Artifact 引用": a dataset-level content hash,
    None before publish, a real SHA-256 hex digest after.
    """
    draft = _draft_dataset_with_case(container, name="content-hash-dataset")
    assert draft.content_hash is None

    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=draft.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=draft.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    assert published.content_hash is not None
    assert len(published.content_hash) == 64  # SHA-256 hex digest length


@pytest.mark.unit
def test_content_hash_is_deterministic_for_identical_content() -> None:
    """Two datasets with the exact same test-case content must hash identically,
    independent of dataset name/version/id — reproducibility, not identity.
    """
    def _case(dataset_id: DatasetId) -> EvaluationTestCase:
        return EvaluationTestCase.create(
            TestCaseId.new_id(), dataset_id, "k1", "s", "", {}, {"classification": "X"}, (), (), False, {},
            Criticality.STANDARD,
        )

    first_hash = _compute_content_hash([_case(DatasetId.new_id())])
    second_hash = _compute_content_hash([_case(DatasetId.new_id())])
    assert first_hash == second_hash
