from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.exceptions import PoisonEventNotFoundException
from memoryknowledge.application.records import PoisonEventRecord
from memoryknowledge.application.services.poison_event_query import PoisonEventQueryService
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import InMemoryPoisonEventRepository
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter

pytestmark = pytest.mark.unit


def _record(payload: str = '{"candidate_text": "plain evidence text"}') -> PoisonEventRecord:
    now = datetime.now(UTC)
    return PoisonEventRecord(
        id=uuid.uuid4(), event_id="evt-1", consumer_name="consume_ticket_memory_source_event", event_type="ticket.resolved.v1",
        payload=payload, error_message="idempotency key reused with a different payload", occurred_at=now, recorded_at=now,
    )


def _build_service() -> tuple[PoisonEventQueryService, InMemoryPoisonEventRepository]:
    repository = InMemoryPoisonEventRepository()
    return PoisonEventQueryService(repository, RegexRedactionPolicyAdapter(), SystemClockAdapter()), repository


def test_list_poison_events_returns_newest_first() -> None:
    service, repository = _build_service()
    older, newer = _record(), _record()
    repository.record(older)
    repository.record(newer)

    views = service.list_poison_events(limit=10)

    assert [v.id for v in views] == [newer.id, older.id]


def test_list_poison_events_redacts_the_payload() -> None:
    """11-security §"Data Protection": a poisoned delivery is by definition
    unvalidated content that could carry anything — reuses the existing
    RedactionPolicyPort, the same as every other outward-facing payload in this
    codebase.
    """
    service, repository = _build_service()
    repository.record(_record(payload='{"candidate_text": "contact me at ops@example.com"}'))

    [view] = service.list_poison_events(limit=10)

    assert "ops@example.com" not in view.payload
    assert "***REDACTED***" in view.payload


def test_mark_quarantined_sets_the_timestamp_and_is_reflected_in_a_later_list() -> None:
    service, repository = _build_service()
    record = _record()
    repository.record(record)

    view = service.mark_quarantined(record.id)

    assert view.quarantined_at is not None
    [listed] = service.list_poison_events(limit=10)
    assert listed.quarantined_at is not None


def test_mark_quarantined_unknown_id_raises_not_found() -> None:
    service, _ = _build_service()

    with pytest.raises(PoisonEventNotFoundException):
        service.mark_quarantined(uuid.uuid4())
