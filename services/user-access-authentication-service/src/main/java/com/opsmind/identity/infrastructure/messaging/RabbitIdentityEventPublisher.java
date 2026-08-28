package com.opsmind.identity.infrastructure.messaging;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.port.out.MessageBrokerPublisherPort;
import com.opsmind.identity.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Real outbound publisher behind {@link MessageBrokerPublisherPort}
 * (SPEC-UA-003). Uses {@link OutboxEventRecord#outboxId()} as the AMQP
 * message id so a retried dispatch of the same row is recognizable as the
 * same logical event by any downstream consumer that deduplicates by
 * message id — mirrors policy-approval-governance-service's own {@code
 * RabbitGovernanceEventPublisher}.
 */
@Component
public class RabbitIdentityEventPublisher implements MessageBrokerPublisherPort {

    private final RabbitTemplate rabbitTemplate;

    public RabbitIdentityEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(OutboxEventRecord record) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(record.outboxId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("eventType", record.eventType());
        properties.setHeader("aggregateType", record.aggregateType());
        properties.setHeader("aggregateId", record.aggregateId());
        properties.setHeader("correlationId", record.correlationId());
        Message message = new Message(record.payloadJson().getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(RabbitConfig.IDENTITY_EVENTS_EXCHANGE, record.eventType(), message);
    }
}
