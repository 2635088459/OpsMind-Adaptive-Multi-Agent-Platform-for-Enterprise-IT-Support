"""13-package-and-class-design §"OutboxPublisher": "Publishes domain outbox
events with retry and publish confirms." 00-implementation-roadmap §"Closure
Principles": "Every published event must go through Gateway outbox." Mirrors
memory-knowledge-service's own ``DispatchOutboxEventsService`` exactly: poll
dispatchable rows, publish through ``EventBusPort``, apply bounded exponential
backoff on failure, dead-letter after the attempt ceiling.
"""

from __future__ import annotations

from datetime import timedelta

from tool_gateway.application.commands import DispatchOutboxCommand
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.ports.event_bus_port import EventBusPort
from tool_gateway.ports.storage_port import ClockPort, OutboxRepository

_MAX_ATTEMPTS = 8
_BASE_BACKOFF_SECONDS = 2


class PublishOutboxService:
    def __init__(
        self, outbox_repository: OutboxRepository, event_bus_port: EventBusPort, clock: ClockPort,
        telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._outbox_repository = outbox_repository
        self._event_bus_port = event_bus_port
        self._clock = clock
        self._telemetry = telemetry

    def dispatch(self, command: DispatchOutboxCommand) -> int:
        now = self._clock.now()
        dispatchable = self._outbox_repository.find_dispatchable(now, command.batch_size)
        # 12-observability §"Metrics": "tool_outbox_pending_count" — a
        # lower-bound snapshot as of this scan (rows this call itself is about
        # to publish/back off, not a live COUNT(*) query — see
        # ``ToolGatewayTelemetry``'s own module docstring).
        self._telemetry.set_outbox_pending(len(dispatchable))
        published_count = 0
        for record in dispatchable:
            if self._event_bus_port.publish(record):
                self._outbox_repository.mark_published(record.outbox_id, now)
                published_count += 1
                continue

            next_attempts = record.attempts + 1
            self._telemetry.record_outbox_publish_failure()
            if next_attempts >= _MAX_ATTEMPTS:
                self._outbox_repository.mark_dead_letter(record.outbox_id)
            else:
                backoff = timedelta(seconds=_BASE_BACKOFF_SECONDS * (2 ** record.attempts))
                self._outbox_repository.mark_failed(record.outbox_id, now + backoff, next_attempts)
        return published_count
