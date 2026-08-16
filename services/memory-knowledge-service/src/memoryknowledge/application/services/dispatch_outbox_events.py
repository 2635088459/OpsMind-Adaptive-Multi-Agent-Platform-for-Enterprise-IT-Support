"""13-package-and-class-design lists dispatch_outbox_events.py alongside the other
application services without naming a matching "Input ports" bullet — mirrors
agent-runtime-service's own DispatchOutboxEventsService/OutboxDispatchPort precedent
exactly (an operational surface, not a domain use case).
"""

from __future__ import annotations

from datetime import timedelta

from memoryknowledge.application.ports_out import ClockPort, EventPublisherPort, OutboxRepository
from memoryknowledge.application.views import DispatchReport

_MAX_ATTEMPTS = 5
_BASE_BACKOFF_SECONDS = 30


class DispatchOutboxEventsService:
    def __init__(self, outbox_repository: OutboxRepository, event_publisher_port: EventPublisherPort, clock: ClockPort) -> None:
        self._outbox_repository = outbox_repository
        self._event_publisher_port = event_publisher_port
        self._clock = clock

    def dispatch_due_events(self, batch_size: int) -> DispatchReport:
        now = self._clock.now()
        due = self._outbox_repository.find_dispatchable(now, batch_size)
        published = failed = dead_lettered = 0

        for record in due:
            if self._event_publisher_port.publish(record):
                self._outbox_repository.mark_published(record.outbox_id, now)
                published += 1
                continue

            attempts = record.attempts + 1
            if attempts >= _MAX_ATTEMPTS:
                self._outbox_repository.mark_dead_letter(record.outbox_id)
                dead_lettered += 1
            else:
                backoff = timedelta(seconds=_BASE_BACKOFF_SECONDS * attempts)
                self._outbox_repository.mark_failed(record.outbox_id, now + backoff, attempts)
                failed += 1

        return DispatchReport(scanned=len(due), published=published, failed=failed, dead_lettered=dead_lettered)
