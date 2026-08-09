package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalationResumed;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-032 event-contract: maps {@link TicketEscalationResumed} to a {@code ticket.escalation-resumed.v1} Outbox entry. */
@Component
public class TicketEscalationResumedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketEscalationResumed event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", event.teamId());
        payload.put("supportQueueId", event.supportQueueId() == null ? null : event.supportQueueId().toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("resolutionCycleId", event.resolutionCycleId().toString());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("resumeReasonCode", event.resumeReasonCode().name());
        payload.put("resumeReason", event.resumeReason());
        payload.put("resumedBy", event.resumedById());
        payload.put("resumedAt", event.resumedAt().toString());
        payload.put("ownershipStatus", event.ownershipStatus().name());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.escalation-resumed",
            "1.0",
            "ticket.escalation-resumed.v1",
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
