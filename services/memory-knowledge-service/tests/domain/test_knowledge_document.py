from __future__ import annotations

from datetime import UTC, datetime

import pytest

from memoryknowledge.domain.exceptions import InvalidDocumentIngestionTransitionException
from memoryknowledge.domain.ids import KnowledgeDocumentId
from memoryknowledge.domain.knowledge_document import KnowledgeDocument

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _received() -> KnowledgeDocument:
    return KnowledgeDocument.receive(
        KnowledgeDocumentId.new_id(), "confluence", "KB-100", "VPN Runbook", "RUNBOOK", (), 1, "hash-1", _now(),
    )


def test_full_ingestion_pipeline_received_to_active() -> None:
    document = _received()
    document = document.mark_parsed()
    document = document.mark_chunked()
    document = document.mark_embedded()
    document = document.mark_indexed()
    document = document.activate()

    assert document.ingestion_status.name == "ACTIVE"


def test_cannot_chunk_before_parsed() -> None:
    document = _received()
    with pytest.raises(InvalidDocumentIngestionTransitionException):
        document.mark_chunked()


def test_activate_requires_indexed() -> None:
    document = _received().mark_parsed().mark_chunked().mark_embedded()
    with pytest.raises(InvalidDocumentIngestionTransitionException):
        document.activate()


def test_mark_failed_from_any_pre_active_step_records_reason() -> None:
    document = _received().mark_parsed()
    failed = document.mark_failed("parser crashed")

    assert failed.ingestion_status.name == "FAILED"
    assert failed.failure_reason == "parser crashed"


def test_mark_failed_after_active_is_rejected() -> None:
    document = _received().mark_parsed().mark_chunked().mark_embedded().mark_indexed().activate()
    with pytest.raises(InvalidDocumentIngestionTransitionException):
        document.mark_failed("too late")


def test_supersede_and_expire_require_active() -> None:
    active = _received().mark_parsed().mark_chunked().mark_embedded().mark_indexed().activate()

    superseded = active.supersede()
    assert superseded.ingestion_status.name == "SUPERSEDED"

    with pytest.raises(InvalidDocumentIngestionTransitionException):
        superseded.expire()


def test_retry_from_failed_clears_reason_and_adopts_the_new_content_hash() -> None:
    """SPEC-MK-030 10-failure-handling §"Poison Document": "可由 admin 修正 metadata 或
    content 后重试."
    """
    failed = _received().mark_parsed().mark_failed("parser crashed")

    retried = failed.retry("corrected-hash-1")

    assert retried.ingestion_status.name == "RECEIVED"
    assert retried.failure_reason is None
    assert retried.content_hash == "corrected-hash-1"


def test_retry_requires_failed_status() -> None:
    active = _received().mark_parsed().mark_chunked().mark_embedded().mark_indexed().activate()
    with pytest.raises(InvalidDocumentIngestionTransitionException):
        active.retry("some-hash")
