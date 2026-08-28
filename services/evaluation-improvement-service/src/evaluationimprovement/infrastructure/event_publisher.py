"""08-transaction-and-outbox §"Outbox 发布": placeholder adapter for EventPublisherPort
— mirrors memory-knowledge-service's own LoggingEventPublisherAdapter exactly. Never
calls a real broker: it logs and always reports success.

SPEC-EI-003 (outbox-processed-event-audit-baseline) gave OutboxRepository itself a
real Postgres backend but deliberately left this adapter as-is — its own scope is
"outbox, idempotent event consumption, audit baseline" (durable persistence), not the
wire-level publisher. Mirrors memory-knowledge-service's own precedent exactly: its
SPEC-MK-003 originally claimed the real RabbitMQ publisher would land there too, and
it didn't — every phase through SPEC-MK-030 kept LoggingEventPublisherAdapter as the
only implementation, deferring each time, until SPEC-MK-031
(final-verification-release) actually built it. Real RabbitMQ wiring here is likewise
a later spec's job, not assumed to be this one's.
"""

from __future__ import annotations

import logging

from evaluationimprovement.application.records import OutboxRecord

logger = logging.getLogger(__name__)


class LoggingEventPublisherAdapter:
    def publish(self, record: OutboxRecord) -> bool:
        logger.info(
            "outbox event published outbox_id=%s event_type=%s aggregate_id=%s correlation_id=%s",
            record.outbox_id, record.event_type, record.aggregate_id, record.correlation_id,
        )
        return True
