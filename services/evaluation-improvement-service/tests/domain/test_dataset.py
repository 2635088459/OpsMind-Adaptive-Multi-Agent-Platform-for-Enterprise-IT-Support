from __future__ import annotations

from datetime import UTC, datetime

import pytest

from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.exceptions import DatasetHasNoTestCasesException, SelfReviewNotAllowedException
from evaluationimprovement.domain.ids import DatasetId
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException

_NOW = datetime.now(UTC)


def _dataset(case_count: int = 5) -> EvaluationDataset:
    dataset = EvaluationDataset.create(
        DatasetId.new_id(), "identity-mfa-golden", "2026.08.1", "IDENTITY_ACCESS", ("mfa",), "author-1", _NOW,
    )
    return dataset.with_case_count(case_count)


@pytest.mark.unit
def test_publish_requires_a_different_actor_than_creator() -> None:
    dataset = _dataset().start_review()
    with pytest.raises(SelfReviewNotAllowedException):
        dataset.publish("author-1", _NOW)


@pytest.mark.unit
def test_publish_by_a_different_reviewer_succeeds() -> None:
    dataset = _dataset().start_review()
    published = dataset.publish("reviewer-1", _NOW)
    assert published.status.value == "PUBLISHED"
    assert published.published_by == "reviewer-1"


@pytest.mark.unit
def test_publish_requires_at_least_one_test_case() -> None:
    dataset = _dataset(case_count=0).start_review()
    with pytest.raises(DatasetHasNoTestCasesException):
        dataset.publish("reviewer-1", _NOW)


@pytest.mark.unit
def test_published_dataset_cannot_go_back_to_draft() -> None:
    published = _dataset().start_review().publish("reviewer-1", _NOW)
    with pytest.raises(InvalidStateTransitionException):
        published.start_review()


@pytest.mark.unit
def test_archived_dataset_cannot_republish() -> None:
    published = _dataset().start_review().publish("reviewer-1", _NOW)
    archived = published.deprecate().archive()
    with pytest.raises(InvalidStateTransitionException):
        archived.publish("reviewer-2", _NOW)


@pytest.mark.unit
def test_publish_result_is_immutable_and_original_untouched() -> None:
    """02-business-invariants INV-EI-005: aggregates are frozen — every transition
    returns a new instance.
    """
    reviewing = _dataset().start_review()
    published = reviewing.publish("reviewer-1", _NOW)
    assert reviewing.status.value == "REVIEWING"
    assert published.status.value == "PUBLISHED"


@pytest.mark.unit
def test_reject_review_sends_a_reviewing_dataset_back_to_draft() -> None:
    reviewing = _dataset().start_review()
    rejected = reviewing.reject_review()
    assert rejected.status.value == "DRAFT"


@pytest.mark.unit
def test_a_rejected_dataset_cannot_publish_until_resubmitted() -> None:
    rejected = _dataset().start_review().reject_review()
    with pytest.raises(InvalidStateTransitionException):
        rejected.publish("reviewer-1", _NOW)
    # Resubmitting makes it publishable again.
    resubmitted = rejected.start_review()
    published = resubmitted.publish("reviewer-1", _NOW)
    assert published.status.value == "PUBLISHED"


@pytest.mark.unit
def test_a_draft_dataset_cannot_be_rejected_directly() -> None:
    with pytest.raises(InvalidStateTransitionException):
        _dataset().reject_review()


@pytest.mark.unit
def test_content_hash_is_none_until_published() -> None:
    dataset = _dataset()
    assert dataset.content_hash is None
    reviewing = dataset.start_review()
    assert reviewing.content_hash is None


@pytest.mark.unit
def test_publish_freezes_the_given_content_hash() -> None:
    published = _dataset().start_review().publish("reviewer-1", _NOW, content_hash="abc123")
    assert published.content_hash == "abc123"
