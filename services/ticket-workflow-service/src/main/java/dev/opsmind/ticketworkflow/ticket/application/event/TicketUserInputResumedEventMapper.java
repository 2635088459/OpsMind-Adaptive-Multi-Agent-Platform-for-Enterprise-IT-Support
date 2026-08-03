package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-013 event-contract: maps one {@link TicketUserInputResumed} fact
 * to the two distinct Outbox entries the spec defines — {@code
 * ticket.user-reply-received.v1} (the message was saved) and {@code
 * ticket.user-input-resumed.v1} (the ticket transitioned). Neither payload
 * includes the reply body, per event-contract §"must not contain full
 * message body".
 */
@Component
public class TicketUserInputResumedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry mapReplyReceived(TicketUserInputResumed event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", event.requestId().toString());
        payload.put("messageId", event.messageId().toString());
        payload.put("repliedBy", event.repliedById());
        payload.put("repliedAt", event.repliedAt().toString());

        return build("ticket.user-reply-received", "ticket.user-reply-received.v1", event, payload, traceId, correlationId, causationId);
    }

    public OutboxEventEntry mapResumed(TicketUserInputResumed event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", event.requestId().toString());
        payload.put("messageId", event.messageId().toString());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("resumeReason", event.reasonCode());
        payload.put("resumedAt", event.occurredAt().toString());

        return build("ticket.user-input-resumed", "ticket.user-input-resumed.v1", event, payload, traceId, correlationId, causationId);
    }

    private OutboxEventEntry build(
        String eventType, String routingKey, TicketUserInputResumed event, Map<String, Object> payload,
        String traceId, String correlationId, String causationId
    ) {
        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            eventType,
            "1.0",
            routingKey,
            AGGREGATE_TYPE,
            event.ticketId().toString(),
            event.aggregateVersion(),
            TicketId.of(event.ticketId().value()),
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
