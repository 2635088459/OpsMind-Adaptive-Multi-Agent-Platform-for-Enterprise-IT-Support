package com.opsmind.identity.infrastructure.messaging;

import com.opsmind.identity.application.port.out.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SPEC-UA-001-scoped placeholder: logs instead of durably publishing. The
 * real outbox table + RabbitMQ publisher is SPEC-UA-003's job (Identity
 * Outbox Processed Event And Audit Baseline) — see that spec's own
 * `infrastructure.persistence.adapter` outbox repository and dispatcher.
 */
@Component
public class LoggingEventPublisherAdapter implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisherAdapter.class);

    @Override
    public void publish(String eventType, String aggregateId, String payloadJson) {
        log.info("identity event (not yet durably published, see SPEC-UA-003): eventType={} aggregateId={}", eventType, aggregateId);
    }
}
