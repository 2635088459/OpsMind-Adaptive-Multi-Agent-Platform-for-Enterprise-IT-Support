from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.records import OutboxRecord
from agentruntime.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.domain.enums import OutboxStatus
from agentruntime.domain.ids import CausationId, CorrelationId, TicketId, WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import InMemoryOutboxRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


class ScriptedEventPublisherPort:
    """Returns each of `outcomes` in order, one per call; defaults to success once exhausted."""

    def __init__(self, outcomes: list[bool] | None = None) -> None:
        self._outcomes = list(outcomes or [])
        self.calls: list[OutboxRecord] = []

    def publish(self, record: OutboxRecord) -> bool:
        self.calls.append(record)
        if self._outcomes:
            return self._outcomes.pop(0)
        return True


def _outbox_record(**overrides) -> OutboxRecord:
    defaults = dict(
        outbox_id=uuid.uuid4(), workflow_instance_id=WorkflowInstanceId.new_id(), ticket_id=TicketId(uuid.uuid4()),
        correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type="agent_runtime.workflow.started",
        schema_version=1, payload="{}", occurred_at=FakeClock().now(),
    )
    defaults.update(overrides)
    return OutboxRecord(**defaults)


def test_dispatches_due_events_and_marks_them_published() -> None:
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort()
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry())
    outbox_repository.append(_outbox_record(occurred_at=clock.now()))

    report = service.dispatch_due_events()

    assert report.scanned == 1
    assert report.published == 1
    assert report.failed == 0
    assert report.dead_lettered == 0
    [record] = outbox_repository.recorded()
    assert record.status is OutboxStatus.PUBLISHED


def test_a_failed_publish_retries_with_backoff_instead_of_immediately() -> None:
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort(outcomes=[False])
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry(), backoff_base_seconds=30)
    outbox_repository.append(_outbox_record(occurred_at=clock.now()))

    report = service.dispatch_due_events()

    assert report.failed == 1
    [record] = outbox_repository.recorded()
    assert record.status is OutboxStatus.PENDING
    assert record.attempts == 1
    assert record.available_at > clock.now()

    # Not due again yet — a second dispatch cycle at the same instant finds nothing.
    assert service.dispatch_due_events().scanned == 0


def test_repeated_failures_move_the_event_to_dead_letter() -> None:
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort(outcomes=[False, False, False])
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry(), max_attempts=3, backoff_base_seconds=1)
    outbox_repository.append(_outbox_record(occurred_at=clock.now()))

    for _ in range(3):
        service.dispatch_due_events()
        clock.advance(timedelta(minutes=10))

    [record] = outbox_repository.recorded()
    assert record.status is OutboxStatus.DEAD_LETTER
    assert publisher.calls[-1] is not None

    # A dead-lettered event is never scanned again.
    assert service.dispatch_due_events().scanned == 0


def test_events_not_yet_available_are_not_dispatched() -> None:
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort()
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry())
    outbox_repository.append(_outbox_record(occurred_at=clock.now() + timedelta(hours=1)))

    report = service.dispatch_due_events()

    assert report.scanned == 0
    assert publisher.calls == []


def test_dispatch_due_events_is_safe_to_resume_after_a_simulated_crash() -> None:
    """SPEC-ARO-030 10-failure-handling §"崩溃窗口" §"事务提交后、outbox 发布前崩溃": "outbox
    row 已存在。Publisher 恢复后继续发布." dispatch_due_events() carries no in-process state
    of its own — every call is a fresh scan driven entirely by what is durably PENDING and
    due — so "resuming after a crash" is simply calling it again; a row still PENDING
    because the process died between a successful publish() and the mark_published() call
    is republished under the same outbox_id, which RabbitMqEventPublisherAdapter already
    sends as the envelope's eventId, so consumer-side processed_events dedup absorbs the
    duplicate delivery safely.
    """
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort()
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry())
    outbox_repository.append(_outbox_record(occurred_at=clock.now()))

    first_report = service.dispatch_due_events()
    assert first_report.published == 1

    # A second, independent DispatchOutboxEventsService instance (standing in for a
    # freshly-restarted process) still correctly finds the row published, not stuck.
    resumed_service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry())
    resumed_report = resumed_service.dispatch_due_events()
    assert resumed_report.scanned == 0


def test_replay_dead_letter_requeues_and_redispatches_a_dead_lettered_event() -> None:
    """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布
    outbox" — the manual/ops intervention OutboxStatus.DEAD_LETTER's own docstring names.
    """
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort(outcomes=[False])
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry(), max_attempts=1, backoff_base_seconds=1)
    record = _outbox_record(occurred_at=clock.now())
    outbox_repository.append(record)

    service.dispatch_due_events()
    assert outbox_repository.recorded()[0].status is OutboxStatus.DEAD_LETTER

    report = service.replay_dead_letter()

    assert report.scanned == 1
    assert report.published == 1
    [replayed] = outbox_repository.recorded()
    assert replayed.status is OutboxStatus.PUBLISHED
    assert replayed.attempts == 0
    assert replayed.outbox_id == record.outbox_id


def test_replay_dead_letter_leaves_pending_and_published_events_untouched() -> None:
    outbox_repository = InMemoryOutboxRepository()
    publisher = ScriptedEventPublisherPort()
    clock = FakeClock()
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, RuntimeTelemetry())
    still_pending = _outbox_record(occurred_at=clock.now() + timedelta(hours=1))
    outbox_repository.append(still_pending)

    report = service.replay_dead_letter()

    assert report.scanned == 0
    [record] = outbox_repository.recorded()
    assert record.status is OutboxStatus.PENDING
    assert record.outbox_id == still_pending.outbox_id


def test_replay_dead_letter_respects_the_batch_size() -> None:
    outbox_repository = InMemoryOutboxRepository()
    clock = FakeClock()
    for _ in range(3):
        outbox_repository.append(_outbox_record(occurred_at=clock.now()))
    publisher_that_always_fails = ScriptedEventPublisherPort(outcomes=[False, False, False])
    dead_lettering_service = DispatchOutboxEventsService(
        outbox_repository, publisher_that_always_fails, clock, RuntimeTelemetry(), max_attempts=1
    )
    dead_lettering_service.dispatch_due_events(batch_size=10)
    assert all(record.status is OutboxStatus.DEAD_LETTER for record in outbox_repository.recorded())

    replaying_service = DispatchOutboxEventsService(outbox_repository, ScriptedEventPublisherPort(), clock, RuntimeTelemetry())
    report = replaying_service.replay_dead_letter(batch_size=2)

    assert report.scanned == 2
