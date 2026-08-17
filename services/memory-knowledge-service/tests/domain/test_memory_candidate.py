from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.exceptions import InvalidMemoryCandidateTransitionException, MemoryCandidateMissingSourceRefException
from memoryknowledge.domain.ids import MemoryCandidateId, MemoryId
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.values import RedactionReport, SourceRef

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _extracted() -> MemoryCandidate:
    return MemoryCandidate.extract(
        MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "vpn login fails after mfa reset", "hash-1", _now(),
    )


def test_extract_requires_at_least_one_source_ref() -> None:
    with pytest.raises(MemoryCandidateMissingSourceRefException):
        MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (), "text", "hash-1", _now())


def test_redact_flags_review_required_when_the_report_shows_actual_redactions() -> None:
    """11-security §"Redaction Pipeline" step 4 "Human review for high-risk candidate":
    a candidate whose raw evidence actually contained secret-shaped text is exactly
    that risk signal.
    """
    candidate = _extracted()

    redacted = candidate.redact("api_key: ***REDACTED***", RedactionReport(secret_patterns_matched=("key_value_secret",)))

    assert redacted.review_required is True


def test_redact_leaves_review_required_false_when_nothing_was_redacted() -> None:
    candidate = _extracted()

    redacted = candidate.redact("vpn login fails after mfa reset", RedactionReport())

    assert redacted.review_required is False


def test_full_happy_path_extracted_to_published() -> None:
    candidate = _extracted()
    assert candidate.status.name == "EXTRACTED"

    candidate = candidate.redact("[REDACTED] mfa reset", RedactionReport())
    assert candidate.status.name == "REDACTED"

    candidate = candidate.validate(confidence_score=0.8, source_refs_trusted=True)
    assert candidate.status.name == "VALIDATED"
    assert candidate.confidence_score == 0.8

    candidate = candidate.approve(usefulness_score=0.6)
    assert candidate.status.name == "APPROVED"

    candidate = candidate.publish()
    assert candidate.status.name == "PUBLISHED"


def test_validate_before_redact_is_rejected() -> None:
    candidate = _extracted()
    with pytest.raises(InvalidMemoryCandidateTransitionException):
        candidate.validate(confidence_score=0.5, source_refs_trusted=True)


def test_mark_duplicate_requires_validated_status_and_records_target_memory() -> None:
    candidate = _extracted().redact("x", RedactionReport())
    with pytest.raises(InvalidMemoryCandidateTransitionException):
        candidate.mark_duplicate(MemoryId.new_id())

    validated = candidate.validate(confidence_score=0.9, source_refs_trusted=True)
    memory_id = MemoryId.new_id()
    duplicate = validated.mark_duplicate(memory_id)

    assert duplicate.status.name == "DUPLICATE"
    assert duplicate.duplicate_of_memory_id == memory_id


def test_conflicting_candidate_cannot_publish_without_explicit_approval() -> None:
    candidate = _extracted().redact("x", RedactionReport()).validate(confidence_score=0.9, source_refs_trusted=True)
    conflicting = candidate.mark_conflicting("conflict-set-1")

    assert conflicting.status.name == "CONFLICTING"
    assert conflicting.review_required is True
    with pytest.raises(InvalidMemoryCandidateTransitionException):
        conflicting.publish()

    # Only an explicit approve() call (a human/policy decision) can move it forward.
    approved = conflicting.approve(usefulness_score=0.5)
    assert approved.status.name == "APPROVED"
    assert approved.review_required is False


def test_reject_records_reason_from_any_rejectable_status() -> None:
    candidate = _extracted()
    rejected = candidate.reject("insufficient evidence")

    assert rejected.status.name == "REJECTED"
    assert rejected.rejection_reason == "insufficient evidence"


def test_publish_requires_approved_status() -> None:
    candidate = _extracted()
    with pytest.raises(InvalidMemoryCandidateTransitionException):
        candidate.publish()
