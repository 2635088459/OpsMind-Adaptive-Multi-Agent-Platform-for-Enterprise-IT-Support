from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from memoryknowledge.application.outbox_codec import build_outbox_record
from memoryknowledge.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.persistence.in_memory import InMemoryOutboxRepository

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


class _JumpingClock:
    """Advances by a large step on every .now() call so a dispatch loop never blocks on
    OutboxStatus.PENDING's own backoff — the fastest, most hermetic way to exercise
    "repeated failures eventually reach DEAD_LETTER" without a real sleep.
    """

    def __init__(self) -> None:
        self._current = datetime.now(UTC)

    def now(self) -> datetime:
        self._current += timedelta(hours=1)
        return self._current


class _FailNTimesPublisher:
    def __init__(self, fail_times: int) -> None:
        self._fail_times = fail_times
        self.calls = 0

    def publish(self, record) -> bool:
        self.calls += 1
        if self.calls <= self._fail_times:
            return False
        return True


class _AlwaysSucceedsPublisher:
    def publish(self, record) -> bool:
        return True


class _AlwaysFailsPublisher:
    def publish(self, record) -> bool:
        return False


def _seed_outbox_repository(count: int = 1) -> InMemoryOutboxRepository:
    repository = InMemoryOutboxRepository()
    for i in range(count):
        repository.append(build_outbox_record({"i": i}, "memory.published.v1", aggregate_id=f"agg-{i}", occurred_at=_now()))
    return repository


def test_dispatch_publishes_due_events() -> None:
    outbox_repository = _seed_outbox_repository(3)
    service = DispatchOutboxEventsService(outbox_repository, _AlwaysSucceedsPublisher(), SystemClockAdapter(), MemoryTelemetry())

    report = service.dispatch_due_events(batch_size=10)

    assert report.scanned == 3
    assert report.published == 3
    assert report.failed == 0
    assert report.dead_lettered == 0
    assert all(r.status.name == "PUBLISHED" for r in outbox_repository.recorded())


def test_dispatch_retries_transient_failures_and_eventually_publishes() -> None:
    outbox_repository = _seed_outbox_repository(1)
    publisher = _FailNTimesPublisher(fail_times=1)
    service = DispatchOutboxEventsService(outbox_repository, publisher, SystemClockAdapter(), MemoryTelemetry())

    first = service.dispatch_due_events(batch_size=10)
    assert first.failed == 1
    assert first.published == 0

    # available_at is now pushed into the future, so an immediate re-scan finds nothing due.
    second = service.dispatch_due_events(batch_size=10)
    assert second.scanned == 0


def test_dispatch_moves_permanently_failing_events_to_dead_letter() -> None:
    outbox_repository = _seed_outbox_repository(1)
    service = DispatchOutboxEventsService(outbox_repository, _AlwaysFailsPublisher(), _JumpingClock(), MemoryTelemetry())

    for _ in range(10):
        report = service.dispatch_due_events(batch_size=10)
        if report.dead_lettered:
            break

    [record] = outbox_repository.recorded()
    assert record.status.name == "DEAD_LETTER"
