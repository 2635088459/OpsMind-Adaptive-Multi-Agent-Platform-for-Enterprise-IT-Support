package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssignmentUpdated;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-030 event-contract: maps {@link TicketAssignmentUpdated} to a {@code ticket.assignment-updated.v1} Outbox entry. */
@Component
public class TicketAssignmentUpdatedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketAssignmentUpdated event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", event.newStatus().name());
        payload.put("previousTeamId", event.previousTeamId());
        payload.put("newTeamId", event.newTeamId());
        payload.put("previousSupportQueueId", event.previousSupportQueueId() == null ? null : event.previousSupportQueueId().toString());
        payload.put("newSupportQueueId", event.newSupportQueueId().toString());
        payload.put("previousAssigneeId", event.previousAssigneeId());
        payload.put("newAssigneeId", event.newAssigneeId());
        payload.put("reason", event.reason());
        payload.put("updatedBy", event.updatedById());
        payload.put("updatedAt", event.updatedAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.assignment-updated",
            "1.0",
            "ticket.assignment-updated.v1",
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
