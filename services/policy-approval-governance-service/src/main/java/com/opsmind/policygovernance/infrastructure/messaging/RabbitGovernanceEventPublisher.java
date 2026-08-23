package com.opsmind.policygovernance.infrastructure.messaging;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.port.MessageBrokerPublisherPort;
import com.opsmind.policygovernance.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Real outbound publisher behind {@link MessageBrokerPublisherPort}. Uses
 * {@link OutboxEventRecord#outboxId()} as the AMQP message id (08-transaction-and-outbox:
 * "Publisher must use stable eventId") so a retried dispatch of the same row
 * is recognizable as the same logical event by any downstream consumer that
 * deduplicates by message id.
 */
@Component
public class RabbitGovernanceEventPublisher implements MessageBrokerPublisherPort {

    private final RabbitTemplate rabbitTemplate;

    public RabbitGovernanceEventPublisher(RabbitTemplate rabbitTemplate) {
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
        if (record.causationId() != null) {
            properties.setHeader("causationId", record.causationId());
        }
        Message message = new Message(record.payloadJson().getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, record.eventType(), message);
    }
}
