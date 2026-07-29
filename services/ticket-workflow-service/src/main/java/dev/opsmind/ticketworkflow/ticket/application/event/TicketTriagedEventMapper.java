package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketTriaged;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps {@link TicketTriaged} to a versioned Outbox entry (SPEC-TW-007 §5).
 * {@code eventType}/{@code eventVersion}/{@code routingKey} follow this
 * codebase's established split (unversioned base name + separate version
 * field + versioned routing key, see {@code TicketIntegrationEventMapper})
 * rather than the spec's single fully-versioned {@code eventType} string —
 * consistent with {@code ticket.created} and {@code ticket.message.added}.
 * The payload never includes the free-text {@code reason}, tokens, or
 * secrets.
 */
@Component
public class TicketTriagedEventMapper {

    private static final String EVENT_TYPE = "ticket.triaged";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.triaged.v1";
    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketTriaged event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", event.ticketId().toString());
        payload.put("fromStatus", event.fromStatus().name());
        payload.put("toStatus", event.toStatus().name());
        payload.put("categoryId", event.categoryId().toString());
        payload.put("subcategoryId", event.subcategoryId() == null ? null : event.subcategoryId().toString());
        payload.put("priority", event.priority().name());
        payload.put("supportQueueId", event.supportQueueId().toString());
        payload.put("ticketVersion", event.aggregateVersion());

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
            event.occurredAt(),
            event.occurredAt()
        );
    }
}
