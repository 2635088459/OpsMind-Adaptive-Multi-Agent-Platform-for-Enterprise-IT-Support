"""13-package-and-class-design §"应用层": DispatchOutboxEventsService, the sole
implementation of OutboxDispatchPort. 08-transaction-and-outbox §"Outbox 发布":
"Application Transaction -> write outbox -> commit -> OutboxPublisher publishes ->
mark published."
"""

from __future__ import annotations

from datetime import timedelta

from evaluationimprovement.application.ports_out import ClockPort, EventPublisherPort, OutboxRepository
from evaluationimprovement.application.views import DispatchReport

_MAX_ATTEMPTS_BEFORE_DEAD_LETTER = 5
_BACKOFF_BASE_SECONDS = 30


class DispatchOutboxEventsService:
    def __init__(self, outbox_repository: OutboxRepository, event_publisher: EventPublisherPort, clock: ClockPort) -> None:
        self._outbox_repository = outbox_repository
        self._event_publisher = event_publisher
        self._clock = clock

    def dispatch_due_events(self, batch_size: int) -> DispatchReport:
        now = self._clock.now()
        due = self._outbox_repository.find_dispatchable(now, batch_size)
        dispatched = 0
        failed = 0
        dead_lettered = 0
        for record in due:
            if self._event_publisher.publish(record):
                self._outbox_repository.mark_published(record.outbox_id, self._clock.now())
                dispatched += 1
                continue

            attempts = record.attempts + 1
            if attempts >= _MAX_ATTEMPTS_BEFORE_DEAD_LETTER:
                self._outbox_repository.mark_dead_letter(record.outbox_id)
                dead_lettered += 1
            else:
                backoff = timedelta(seconds=_BACKOFF_BASE_SECONDS * (2 ** (attempts - 1)))
                self._outbox_repository.mark_failed(record.outbox_id, self._clock.now() + backoff, attempts)
                failed += 1

        return DispatchReport(dispatched=dispatched, failed=failed, dead_lettered=dead_lettered)
