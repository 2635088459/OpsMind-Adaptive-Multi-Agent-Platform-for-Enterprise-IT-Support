package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps {@link TicketAssigned}/{@link TicketReassigned}/{@link
 * TicketUnassigned} to versioned Outbox entries (SPEC-TW-008 §2-5).
 * {@code eventType}/{@code eventVersion}/{@code routingKey} follow this
 * codebase's established split, same as {@link
 * dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper}
 * (SPEC-TW-007 deviation #8). The payload never includes tokens, raw
 * claims, or full identity profiles — only the fields event-contract §3-5
 * name.
 */
@Component
public class TicketAssignmentEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry mapAssigned(TicketAssigned event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("reason", event.reason());

        return build("ticket.assigned", "ticket.assigned.v1", event.ticketId().toString(), event.aggregateVersion(),
            event.ticketId().value(), payload, event.occurredAt(), traceId, correlationId, causationId);
    }

    public OutboxEventEntry mapReassigned(TicketReassigned event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("previousAssigneeId", event.previousAssigneeId());
        payload.put("assigneeId", event.assigneeId());
        payload.put("status", event.status().name());
        payload.put("reason", event.reason());

        return build("ticket.reassigned", "ticket.reassigned.v1", event.ticketId().toString(), event.aggregateVersion(),
            event.ticketId().value(), payload, event.occurredAt(), traceId, correlationId, causationId);
    }

    public OutboxEventEntry mapUnassigned(TicketUnassigned event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("previousAssigneeId", event.previousAssigneeId());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("reason", event.reason());

        return build("ticket.unassigned", "ticket.unassigned.v1", event.ticketId().toString(), event.aggregateVersion(),
            event.ticketId().value(), payload, event.occurredAt(), traceId, correlationId, causationId);
    }

    private OutboxEventEntry build(
        String eventType, String routingKey, String aggregateId, long aggregateVersion,
        UUID ticketIdValue, Map<String, Object> payload, Instant occurredAt,
        String traceId, String correlationId, String causationId
    ) {
        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            eventType,
            "1.0",
            routingKey,
            AGGREGATE_TYPE,
            aggregateId,
            aggregateVersion,
            TicketId.of(ticketIdValue),
            null,
            traceId,
            correlationId,
            causationId,
            DATA_CLASSIFICATION,
            payload,
            occurredAt,
            occurredAt
        );
    }
}
