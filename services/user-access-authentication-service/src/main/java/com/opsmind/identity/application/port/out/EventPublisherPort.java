package com.opsmind.identity.application.port.out;

/**
 * 13-package-and-class-design §Output Ports; 08-transaction-and-outbox:
 * "Aggregate state, audit record, and outbox event commit in one PostgreSQL
 * transaction." SPEC-UA-001's own placeholder adapter only logged; the real
 * SPEC-UA-003 adapter ({@code infrastructure.persistence.adapter.OutboxEventPublisherAdapter})
 * durably appends a {@code PENDING} row to {@code outbox_events} in the
 * caller's own transaction — actual RabbitMQ delivery is a separate,
 * later step ({@code application.service.OutboxDispatchService}).
 */
public interface EventPublisherPort {

    void publish(String eventType, String aggregateType, String aggregateId, String payloadJson, String correlationId);
}
