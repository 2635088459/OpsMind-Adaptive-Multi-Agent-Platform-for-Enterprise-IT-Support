package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-012 event-contract: maps {@link TicketUserInputRequested} to a
 * {@code ticket.user-input-requested.v1} Outbox entry. The payload never
 * includes the prompt text itself — only structured metadata, per
 * domain-rules §4 (the prompt may itself be requester-safe, but the event
 * still must not leak it beyond the API/timeline surface it was written for).
 */
@Component
public class TicketUserInputRequestedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketUserInputRequested event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("requestId", event.requestId().toString());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("requestedBy", event.requestedById());
        payload.put("requestedAt", event.requestedAt().toString());
        payload.put("waitingForRequesterSince", event.waitingForRequesterSince().toString());
        payload.put("resumeStatus", event.resumeStatus());
        payload.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.user-input-requested",
            "1.0",
            "ticket.user-input-requested.v1",
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
