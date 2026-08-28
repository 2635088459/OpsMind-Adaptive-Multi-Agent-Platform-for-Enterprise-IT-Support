"""SPEC-EI-003 test-plan §"Integration Tests": "PostgreSQL migration、唯一键、索引和
JSONB 字段" and "outbox publish/replay; processed event 去重" — exercised directly
against the real, migrated schema.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from evaluationimprovement.application.outbox_codec import build_outbox_record
from evaluationimprovement.application.records import AuditRecordEntry, CommandIdempotencyRecord, ProcessedEventRecord
from evaluationimprovement.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from evaluationimprovement.domain.enums import OutboxStatus
from evaluationimprovement.domain.events import EvaluationRunRequested
from evaluationimprovement.domain.ids import CorrelationId, IdempotencyKey, RunId
from evaluationimprovement.infrastructure.persistence.postgres.repositories import (
    PostgresAuditRecordRepository,
    PostgresCommandIdempotencyRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
)

pytestmark = pytest.mark.integration

_NOW = datetime.now(UTC)


def _outbox_record():
    run_id = RunId.new_id()
    return build_outbox_record(
        EvaluationRunRequested(run_id=run_id, run_key="rk-1", occurred_at=_NOW), "evaluation.run.requested.v1",
        aggregate_id=str(run_id), occurred_at=_NOW, correlation_id=CorrelationId.new_id(),
    )


@pytest.mark.integration
def test_outbox_append_and_find_dispatchable_round_trip(session_factory) -> None:
    repo = PostgresOutboxRepository(session_factory)
    record = _outbox_record()
    repo.append(record)

    due = repo.find_dispatchable(_NOW + timedelta(seconds=1), 10)
    assert any(r.outbox_id == record.outbox_id for r in due)
    found = next(r for r in due if r.outbox_id == record.outbox_id)
    assert found.event_type == "evaluation.run.requested.v1"
    assert found.correlation_id == record.correlation_id
    assert found.status is OutboxStatus.PENDING


@pytest.mark.integration
def test_outbox_mark_failed_then_dead_letter_via_real_dispatch_service(session_factory) -> None:
    """08-transaction-and-outbox / 10-failure-handling: the exact scenario
    tests/application/test_dispatch_outbox_events_service.py already proves against
    the in-memory adapter — repeated here against real Postgres to prove
    find_dispatchable() really does re-offer FAILED rows once their own
    `available_at` backoff has passed (the SPEC-EI-001 bug DispatchOutboxEventsService
    found), not just in-memory.
    """

    class _FakeClock:
        def __init__(self, start: datetime) -> None:
            self._now = start

        def now(self) -> datetime:
            return self._now

        def advance(self, delta: timedelta) -> None:
            self._now = self._now + delta

    class _AlwaysFailingPublisher:
        def publish(self, record) -> bool:  # noqa: ANN001, ARG002
            return False

    repo = PostgresOutboxRepository(session_factory)
    clock = _FakeClock(_NOW)
    service = DispatchOutboxEventsService(repo, _AlwaysFailingPublisher(), clock)

    record = _outbox_record()
    repo.append(record)

    for _ in range(5):
        clock.advance(timedelta(hours=1))
        service.dispatch_due_events(batch_size=10)

    dead_letters = repo.find_dead_letter(10)
    assert any(r.outbox_id == record.outbox_id for r in dead_letters)


@pytest.mark.integration
def test_outbox_mark_published(session_factory) -> None:
    repo = PostgresOutboxRepository(session_factory)
    record = _outbox_record()
    repo.append(record)
    repo.mark_published(record.outbox_id, _NOW)

    still_due = repo.find_dispatchable(_NOW + timedelta(seconds=1), 10)
    assert not any(r.outbox_id == record.outbox_id for r in still_due)


@pytest.mark.integration
def test_processed_event_dedup_is_idempotent(session_factory) -> None:
    repo = PostgresProcessedEventRepository(session_factory)
    assert repo.is_processed("evt-1", "consumer-a") is False

    repo.mark_processed(ProcessedEventRecord(event_id="evt-1", consumer_name="consumer-a", event_type="ticket.resolved.v1", processed_at=_NOW))
    assert repo.is_processed("evt-1", "consumer-a") is True
    # A different consumer's own dedup state is independent.
    assert repo.is_processed("evt-1", "consumer-b") is False

    # Re-marking the same (event_id, consumer_name) is a no-op, not a conflict.
    repo.mark_processed(ProcessedEventRecord(event_id="evt-1", consumer_name="consumer-a", event_type="ticket.resolved.v1", processed_at=_NOW))
    assert repo.is_processed("evt-1", "consumer-a") is True


@pytest.mark.integration
def test_command_idempotency_round_trip(session_factory) -> None:
    repo = PostgresCommandIdempotencyRepository(session_factory)
    key = IdempotencyKey(f"candidate:{uuid.uuid4()}")
    assert repo.find_by_key(key) is None

    repo.save(CommandIdempotencyRecord(
        idempotency_key=key, command_type="create_improvement_candidate", target_id=None, request_hash="hash-1",
        response_json='{"ok": true}', created_at=_NOW,
    ))
    found = repo.find_by_key(key)
    assert found is not None
    assert found.request_hash == "hash-1"
    assert found.response_json == '{"ok": true}'


@pytest.mark.integration
def test_audit_records_are_returned_newest_first(session_factory) -> None:
    repo = PostgresAuditRecordRepository(session_factory)
    first = AuditRecordEntry(id=uuid.uuid4(), action="create_dataset", resource_type="EVALUATION_DATASET", resource_id="d1", actor="author-1", outcome="SUCCESS", correlation_id="corr-1", detail="{}", occurred_at=_NOW)
    second = AuditRecordEntry(id=uuid.uuid4(), action="publish_dataset", resource_type="EVALUATION_DATASET", resource_id="d1", actor="reviewer-1", outcome="SUCCESS", correlation_id="corr-1", detail="{}", occurred_at=_NOW + timedelta(seconds=1))
    repo.append(first)
    repo.append(second)

    recent = repo.find_recent(10)
    ids = [r.id for r in recent]
    assert ids.index(second.id) < ids.index(first.id)
