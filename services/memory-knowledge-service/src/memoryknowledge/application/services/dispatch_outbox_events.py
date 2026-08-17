"""13-package-and-class-design lists dispatch_outbox_events.py alongside the other
application services without naming a matching "Input ports" bullet — mirrors
agent-runtime-service's own DispatchOutboxEventsService/OutboxDispatchPort precedent
exactly (an operational surface, not a domain use case).
"""

from __future__ import annotations

import dataclasses
from datetime import datetime, timedelta

from memoryknowledge.application.ports_out import ClockPort, EventPublisherPort, OutboxRepository
from memoryknowledge.application.records import OutboxRecord
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.application.views import DispatchReport
from memoryknowledge.domain.enums import OutboxStatus

_MAX_ATTEMPTS = 5
_BASE_BACKOFF_SECONDS = 30


class DispatchOutboxEventsService:
    def __init__(
        self, outbox_repository: OutboxRepository, event_publisher_port: EventPublisherPort, clock: ClockPort,
        telemetry: MemoryTelemetry,
    ) -> None:
        self._outbox_repository = outbox_repository
        self._event_publisher_port = event_publisher_port
        self._clock = clock
        self._telemetry = telemetry

    def dispatch_due_events(self, batch_size: int) -> DispatchReport:
        now = self._clock.now()
        due = self._outbox_repository.find_dispatchable(now, batch_size)
        return self._dispatch(due, now)

    def replay_dead_letter(self, batch_size: int) -> DispatchReport:
        """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher": "replay 必须幂等" —
        requeues up to `batch_size` DEAD_LETTER rows (attempts reset to 0) and attempts
        to publish them immediately, reporting the real outcome rather than just moving
        them back to PENDING and waiting for the next dispatch_due_events() scan.
        """
        now = self._clock.now()
        dead_lettered_records = self._outbox_repository.find_dead_letter(batch_size)
        requeued: list[OutboxRecord] = []
        for record in dead_lettered_records:
            self._outbox_repository.requeue(record.outbox_id, now)
            # requeue() resets attempts/status/available_at at the storage layer; mirror
            # that locally so _dispatch()'s own backoff math starts from a clean slate,
            # not the DEAD_LETTER row's stale attempts count.
            requeued.append(dataclasses.replace(record, status=OutboxStatus.PENDING, attempts=0, available_at=now))
        return self._dispatch(requeued, now)

    def _dispatch(self, records: list[OutboxRecord], now: datetime) -> DispatchReport:
        published = failed = dead_lettered = 0

        for record in records:
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

        # "memory_outbox_backlog" (12-observability §"Metrics"): a lower-bound snapshot
        # as of this scan (rows this call itself just backed off), not a live COUNT(*)
        # — mirrors agent-runtime-service's own RuntimeTelemetry.set_outbox_pending()
        # exactly, including its own docstring caveat.
        self._telemetry.set_outbox_backlog(failed)
        return DispatchReport(scanned=len(records), published=published, failed=failed, dead_lettered=dead_lettered)
