"""08-transaction-and-outbox §"Outbox Publisher": placeholder adapter for
EventPublisherPort — mirrors LoggingToolGatewayPort's role exactly. This adapter
never calls a real broker: it logs and always reports success. phase-07
(runtime-event-publishing) replaces this with the real RabbitMQ adapter (per the
frozen Python Agent Runtime baseline's "RabbitMQ async client" dependency).
"""

from __future__ import annotations

import logging

from agentruntime.application.records import OutboxRecord

logger = logging.getLogger(__name__)


class LoggingEventPublisherAdapter:
    def publish(self, record: OutboxRecord) -> bool:
        logger.info(
            "outbox event published outbox_id=%s event_type=%s workflow_instance_id=%s correlation_id=%s",
            record.outbox_id, record.event_type, record.workflow_instance_id, record.correlation_id,
        )
        return True
