package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReopened;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-011 event-contract §3: maps {@link TicketReopened} to a {@code ticket.reopened.v1} Outbox entry. */
@Component
public class TicketReopenedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketReopened event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("previousResolutionCycleId", event.previousResolutionCycleId().toString());
        payload.put("newResolutionCycleId", event.newResolutionCycleId().toString());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("reopenReasonCode", event.reopenReasonCode().name());
        payload.put("reopenCount", event.reopenCount());
        payload.put("reopenedBy", event.reopenedById());
        payload.put("reopenedAt", event.reopenedAt().toString());
        payload.put("ownershipStatus", event.ownershipStatus().name());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.reopened",
            "1.0",
            "ticket.reopened.v1",
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
