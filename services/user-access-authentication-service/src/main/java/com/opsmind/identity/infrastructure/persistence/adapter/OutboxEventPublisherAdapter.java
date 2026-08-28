package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.model.OutboxEventStatus;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.OutboxEventRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * The real {@link EventPublisherPort} (SPEC-UA-003): durably appends a
 * {@code PENDING} row to {@code outbox_events} in the caller's own
 * transaction (08-transaction-and-outbox), replacing SPEC-UA-001's
 * log-only placeholder. Actual RabbitMQ delivery is a later, separate step
 * ({@link com.opsmind.identity.application.service.OutboxDispatchService}) — no network call ever happens inside the
 * business transaction this method participates in.
 */
@Component
public class OutboxEventPublisherAdapter implements EventPublisherPort {

    private static final String EVENT_VERSION = "v1";

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    public OutboxEventPublisherAdapter(OutboxEventRepository outboxEventRepository, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
    }

    @Override
    public void publish(String eventType, String aggregateType, String aggregateId, String payloadJson, String correlationId) {
        java.time.Instant now = clock.instant();
        outboxEventRepository.append(new OutboxEventRecord(
            UUID.randomUUID().toString(), aggregateType, aggregateId, eventType, EVENT_VERSION, payloadJson,
            correlationId, OutboxEventStatus.PENDING, 0, now, null, now
        ));
    }
}
