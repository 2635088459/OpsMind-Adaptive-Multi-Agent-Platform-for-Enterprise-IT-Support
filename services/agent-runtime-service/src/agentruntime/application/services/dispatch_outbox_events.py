"""13-package-and-class-design §"Application Layer" analogue for the outbox side:
DispatchOutboxEventsService, the sole implementation of OutboxDispatchPort and the
sole caller of EventPublisherPort. 08-transaction-and-outbox §"Outbox Publisher":
"Publisher handles only outbox rows created by committed transactions ... Scan by
available_at. Support retry/backoff. Mark published_at after success. Move to
DEAD_LETTER after repeated failures. Publish with event id so consumers can
de-duplicate" (agentruntime.application.records.OutboxRecord.outbox_id is that event id
— RabbitMqEventPublisherAdapter already publishes it as the envelope's eventId, so a
row republished under the same outbox_id, whether by an ordinary retry or by
replay_dead_letter() below, is safely de-duplicated on the consumer side; nothing
publisher-side needs to change to make dispatch_due_events() itself crash-recoverable
either — every call is a fresh, stateless scan driven entirely by what is durably
PENDING and due, so resuming after a crash mid-batch is just calling it again).

Nothing schedules dispatch_due_events() periodically yet — phase-07
(runtime-event-publishing) owns that; for now it is invoked on demand through
POST /internal/agent-runtime/v1/admin/outbox/dispatch.

SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3 ("重放未发布 outbox")
adds replay_dead_letter(): OutboxStatus.DEAD_LETTER's own docstring already named the
gap this closes ("requires manual/ops intervention") — dispatch_due_events() only ever
scans PENDING rows, so a row that exhausted its retry budget had no path back at all
until now.
"""

from __future__ import annotations

import logging
from datetime import timedelta

from opentelemetry import trace

from agentruntime.application.ports_out import ClockPort, EventPublisherPort, OutboxRepository
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import DispatchReport

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_DEFAULT_BATCH_SIZE = 50
_DEFAULT_MAX_ATTEMPTS = 5
_DEFAULT_BACKOFF_BASE_SECONDS = 30


class DispatchOutboxEventsService:
    def __init__(
        self,
        outbox_repository: OutboxRepository,
        event_publisher_port: EventPublisherPort,
        clock: ClockPort,
        telemetry: RuntimeTelemetry,
        max_attempts: int = _DEFAULT_MAX_ATTEMPTS,
        backoff_base_seconds: int = _DEFAULT_BACKOFF_BASE_SECONDS,
    ) -> None:
        self._outbox_repository = outbox_repository
        self._event_publisher_port = event_publisher_port
        self._clock = clock
        self._telemetry = telemetry
        self._max_attempts = max_attempts
        self._backoff_base_seconds = backoff_base_seconds

    def dispatch_due_events(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> DispatchReport:
        with tracer.start_as_current_span("event.published"):
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
                    self._telemetry.record_outbox_publish_failed()
                else:
                    backoff = timedelta(seconds=self._backoff_base_seconds * (2 ** (attempts - 1)))
                    self._outbox_repository.mark_failed(record.outbox_id, now + backoff, attempts)
                    failed += 1
                    self._telemetry.record_outbox_publish_failed()

            # "agent_runtime_outbox_pending" (12-observability §"Metrics"): rows still
            # PENDING as of this scan — failed (backed off) + whatever this batch_size
            # cutoff left unscanned beyond it is not visible to us, so this is a
            # lower-bound snapshot as of *this* scan, not a live query; still/failed
            # rows this call itself just backed off are exactly what remains pending
            # right now from this vantage point.
            self._telemetry.set_outbox_pending(failed)
            logger.info(
                "action=dispatch_outbox_events status=completed scanned=%s published=%s failed=%s dead_lettered=%s",
                len(due), published, failed, dead_lettered,
            )
            return DispatchReport(scanned=len(due), published=published, failed=failed, dead_lettered=dead_lettered, dispatched_at=now)

    def replay_dead_letter(self, batch_size: int = _DEFAULT_BATCH_SIZE) -> DispatchReport:
        """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布
        outbox" — the manual/ops intervention OutboxStatus.DEAD_LETTER's own docstring
        names. Requeues up to `batch_size` DEAD_LETTER rows back to PENDING (attempts
        reset to 0, available_at reset to now) then immediately runs the exact same
        dispatch_due_events() pass a fresh row would go through — a replay that fails
        again re-enters the same retry/backoff/dead-letter cycle rather than a bespoke
        one-shot attempt. The returned DispatchReport's `scanned` count reflects
        whatever dispatch_due_events() itself finds due at that moment (the
        just-requeued rows plus any other already-pending ones), the same aggregate-
        outcome reporting every other batch scanner in this module already uses.
        """
        now = self._clock.now()
        for record in self._outbox_repository.find_dead_letter(batch_size):
            self._outbox_repository.requeue(record.outbox_id, now)

        return self.dispatch_due_events(batch_size)
