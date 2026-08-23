"""The safe default ``EventBusPort`` adapter — logs instead of publishing to a
real broker. Not literally named by 13-package-and-class-design's own
``adapters/events/`` tree (which lists only ``rabbitmq_publisher.py``/
``rabbitmq_consumer.py``), added the same way memory-knowledge-service's own
``infrastructure/event_publisher.py`` (``LoggingEventPublisherAdapter``) predates
its real RabbitMQ adapter: every hermetic unit test/local run that boots the
container without opting into "rabbitmq" must not silently attempt a real
broker connection — a service that merely logs an unpublished event on an
unconfigured host is a safe, inert default, unlike falling back to non-durable
storage.
"""

from __future__ import annotations

import logging

from tool_gateway.domain.records import OutboxRecord

logger = logging.getLogger("tool_gateway.outbox")


class LoggingEventBusAdapter:
    def publish(self, record: OutboxRecord) -> bool:
        logger.info(
            "outbox event published (logging adapter): type=%s aggregate_id=%s outbox_id=%s",
            record.event_type, record.aggregate_id, record.outbox_id,
        )
        return True
