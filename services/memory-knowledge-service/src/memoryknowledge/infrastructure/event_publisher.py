"""08-transaction-and-outbox (deferred detail to SPEC-MK-003) §"Outbox Publisher":
placeholder adapter for EventPublisherPort — mirrors agent-runtime-service's own
LoggingEventPublisherAdapter exactly. Never calls a real broker: it logs and always
reports success. SPEC-MK-003 (outbox/idempotency/audit baseline) replaces this with the
real RabbitMQ adapter, per the frozen Python baseline's "RabbitMQ async client".
"""

from __future__ import annotations

import logging

from memoryknowledge.application.records import OutboxRecord

logger = logging.getLogger(__name__)


class LoggingEventPublisherAdapter:
    def publish(self, record: OutboxRecord) -> bool:
        logger.info(
            "outbox event published outbox_id=%s event_type=%s aggregate_id=%s correlation_id=%s",
            record.outbox_id, record.event_type, record.aggregate_id, record.correlation_id,
        )
        return True
