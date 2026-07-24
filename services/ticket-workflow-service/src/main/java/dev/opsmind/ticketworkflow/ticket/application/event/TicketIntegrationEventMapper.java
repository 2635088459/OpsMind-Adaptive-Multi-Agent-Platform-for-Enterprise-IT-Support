package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps Ticket domain events to versioned Outbox entries. The mapper never
 * touches RabbitMQ or blocks on broker availability; it only shapes the
 * payload that {@code OutboxEventRepository} persists in the same
 * transaction as the business change (BI-095).
 */
@Component
public class TicketIntegrationEventMapper {

    private static final String EVENT_TYPE = "ticket.created";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.created.v1";
    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    private final RequesterPseudonymizer requesterPseudonymizer;

    public TicketIntegrationEventMapper(RequesterPseudonymizer requesterPseudonymizer) {
        this.requesterPseudonymizer = requesterPseudonymizer;
    }

    public OutboxEventEntry mapTicketCreated(
        TicketCreated event,
        String traceId,
        String correlationId,
        String causationId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("displayId", event.displayId().value());
        payload.put("requesterIdHash", requesterPseudonymizer.pseudonymize(event.requesterId()));
        payload.put("applicationCode", event.applicationCode().name());
        payload.put("source", event.source().name());
        payload.put("initialStatus", "NEW");
        payload.put("createdAt", event.createdAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            EVENT_VERSION,
            ROUTING_KEY,
            AGGREGATE_TYPE,
            event.ticketId().toString(),
            event.aggregateVersion(),
            event.ticketId(),
            null,
            traceId,
            correlationId,
            causationId,
            DATA_CLASSIFICATION,
            payload,
            event.createdAt(),
            event.createdAt()
        );
    }
}
