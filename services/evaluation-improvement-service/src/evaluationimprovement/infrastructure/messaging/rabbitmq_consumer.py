"""13-package-and-class-design `infrastructure/messaging/rabbitmq_consumer.py`. A real
AMQP consumer for the events 06-event-contracts lists is still not built here —
consistent with every other Python service in this repo (agent-runtime-service,
memory-knowledge-service), none of which wires a real broker consumer either; each
instead exposes a REST "event listener" endpoint a future consumer calls into. That
endpoint now exists for this domain too: interfaces.event.router (SPEC-EI-030/031/032),
backed by application.services.consume_cross_domain_event/
consume_approval_decision_event. This module remains an empty placeholder marking
where the real AMQP wiring (pika/aio-pika consumer -> the same application-layer
consume_*() methods the REST router already calls) would replace that REST stand-in.
"""

from __future__ import annotations
