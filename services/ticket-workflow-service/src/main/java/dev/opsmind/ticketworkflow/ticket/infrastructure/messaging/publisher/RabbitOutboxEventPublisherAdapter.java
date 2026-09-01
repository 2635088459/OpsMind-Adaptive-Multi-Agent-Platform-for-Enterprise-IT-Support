package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventPublisher;
import dev.opsmind.ticketworkflow.configuration.RabbitMqConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project-level integration verification (2026-09-01): the first real
 * outbox-to-broker publisher this service has ever had — see {@code
 * OutboxDispatchApplicationService}'s own javadoc for the full "why this
 * was missing" writeup. Publishes to the same {@code opsmind.events}
 * exchange {@link RabbitMqConfiguration} already declares for this
 * service's own inbound consumers, using each row's own {@code
 * routing_key} column (already correctly populated per event type since
 * whichever spec introduced it — this was always write-ready, just never
 * read).
 * <p>
 * The envelope shape matches {@code
 * policygovernance.application.OutboxDispatchService#buildPayload} /
 * {@code ConsumedEventEnvelope} field-for-field (both sides of this
 * project's shared 06-event-contracts §Envelope), so any consumer already
 * built against that shape (e.g. policy-approval-governance-service's own
 * {@code TicketApprovalRequiredEventConsumer}) can parse what this service
 * now actually publishes — whether or not its own payload additionally
 * satisfies that specific consumer's per-event-type contract is a
 * separate, further concern this adapter does not attempt to solve.
 */
@Component
public class RabbitOutboxEventPublisherAdapter implements OutboxEventPublisher {

    private static final String PRODUCER = "ticket-workflow-service";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitOutboxEventPublisherAdapter(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEventEntry entry) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", entry.eventId());
        envelope.put("eventType", entry.eventType());
        envelope.put("producer", PRODUCER);
        envelope.put("schemaVersion", parseSchemaVersion(entry.eventVersion()));
        envelope.put("aggregateId", entry.aggregateId());
        envelope.put("ticketId", entry.ticketId().value().toString());
        envelope.put("correlationId", entry.correlationId());
        if (entry.causationId() != null) {
            envelope.put("causationId", entry.causationId());
        }
        envelope.put("occurredAt", entry.createdAt().toString());
        envelope.put("payload", entry.payload());

        String body = serialize(envelope);
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, entry.routingKey(), body);
    }

    private int parseSchemaVersion(String eventVersion) {
        if (eventVersion == null) {
            return 1;
        }
        String digits = eventVersion.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String serialize(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox envelope", e);
        }
    }
}
