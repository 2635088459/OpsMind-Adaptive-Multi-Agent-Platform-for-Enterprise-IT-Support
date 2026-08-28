"""13-package-and-class-design `infrastructure/messaging/rabbitmq_publisher.py`. The
real RabbitMQ-backed EventPublisherPort adapter is SPEC-EI-003
(outbox-processed-event-audit-baseline) scope, mirroring memory-knowledge-service's
own SPEC-MK-003/SPEC-MK-031 split. SPEC-EI-001 ships only
infrastructure.event_publisher.LoggingEventPublisherAdapter; this module is an empty
placeholder marking where the real adapter will live.
"""

from __future__ import annotations
