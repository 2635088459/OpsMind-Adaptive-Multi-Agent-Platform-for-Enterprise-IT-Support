"""13-package-and-class-design §"OutboxPublisher": "Publishes domain outbox
events with retry and publish confirms." 00-implementation-roadmap §"Closure
Principles": "Every published event must go through Gateway outbox." Real
RabbitMQ wiring is SPEC-TG-003 scope (phase-00's own outbox/processed-event/audit
baseline spec) — mirrors memory-knowledge-service's own EventPublisherPort split
exactly.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.domain.records import OutboxRecord


class EventBusPort(Protocol):
    def publish(self, record: OutboxRecord) -> bool:
        """Returns True on success. Must never raise for an ordinary delivery
        failure — the publish_outbox use case interprets False as "retry with
        backoff".
        """
        ...
