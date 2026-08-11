"""13-package-and-class-design §"Application Layer" analogue for the outbox side:
DispatchOutboxEventsService, the sole implementation of OutboxDispatchPort and the
sole caller of EventPublisherPort. 08-transaction-and-outbox §"Outbox Publisher":
"Publisher handles only outbox rows created by committed transactions ... Scan by
available_at. Support retry/backoff. Mark published_at after success. Move to
DEAD_LETTER after repeated failures. Publish with event id so consumers can
de-duplicate" (agentruntime.application.records.OutboxRecord.outbox_id is that event id).

Nothing schedules this periodically yet — phase-07 (runtime-event-publishing) owns
that; for now it is invoked on demand through
POST /internal/agent-runtime/v1/admin/outbox/dispatch.
"""

from __future__ import annotations

from datetime import timedelta

from agentruntime.application.ports_out import ClockPort, EventPublisherPort, OutboxRepository
from agentruntime.application.views import DispatchReport

_DEFAULT_BATCH_SIZE = 50
_DEFAULT_MAX_ATTEMPTS = 5
_DEFAULT_BACKOFF_BASE_SECONDS = 30


class DispatchOutboxEventsService:
    def __init__(
        self,
        outbox_repository: OutboxRepository,
        event_publisher_port: EventPublisherPort,
        clock: ClockPort,
        max_attempts: int = _DEFAULT_MAX_ATTEMPTS,
        backoff_base_seconds: int = _DEFAULT_BACKOFF_BASE_SECONDS,
    ) -> None:
        self._outbox_repository = outbox_repository
        self._event_publisher_port = event_publisher_port
        self._clock = clock
        self._max_attempts = max_attempts
        self._backoff_base_seconds = backoff_base_seconds

    def dispatch_due_events(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> DispatchReport:
        now = self._clock.now()
        due = self._outbox_repository.find_dispatchable(now, batch_size)

        published = failed = dead_lettered = 0
        for record in due:
            if self._event_publisher_port.publish(record):
                self._outbox_repository.mark_published(record.outbox_id, now)
                published += 1
                continue

            attempts = record.attempts + 1
            if attempts >= self._max_attempts:
                self._outbox_repository.mark_dead_letter(record.outbox_id)
                dead_lettered += 1
            else:
                backoff = timedelta(seconds=self._backoff_base_seconds * (2 ** (attempts - 1)))
                self._outbox_repository.mark_failed(record.outbox_id, now + backoff, attempts)
                failed += 1

        return DispatchReport(scanned=len(due), published=published, failed=failed, dead_lettered=dead_lettered, dispatched_at=now)
