package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps {@link TicketMessageAdded} to a versioned Outbox entry (SPEC-TW-004
 * §15). The payload never includes message content, title, description,
 * raw author ID, email, JWT, or Idempotency Key.
 */
@Component
public class TicketMessageAddedEventMapper {

    private static final String EVENT_TYPE = "ticket.message.added";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.message.added.v1";
    private static final String AGGREGATE_TYPE = "TicketMessage";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketMessageAdded event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", event.messageId().toString());
        payload.put("ticketId", event.ticketId().toString());
        payload.put("messageType", event.messageType().name());
        payload.put("visibility", event.visibility().name());
        payload.put("authorType", event.authorType());
        payload.put("createdAt", event.createdAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            EVENT_VERSION,
            ROUTING_KEY,
            AGGREGATE_TYPE,
            event.messageId().toString(),
            0L,
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
