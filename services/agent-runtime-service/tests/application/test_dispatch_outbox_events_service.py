from __future__ import annotations

import uuid
from datetime import timedelta

import pytest

from agentruntime.application.records import OutboxRecord
from agentruntime.application.services.dispatch_outbox_events import DispatchOutboxEventsService
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
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock)
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
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, backoff_base_seconds=30)
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
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock, max_attempts=3, backoff_base_seconds=1)
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
    service = DispatchOutboxEventsService(outbox_repository, publisher, clock)
    outbox_repository.append(_outbox_record(occurred_at=clock.now() + timedelta(hours=1)))

    report = service.dispatch_due_events()

    assert report.scanned == 0
    assert publisher.calls == []
