package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class TicketStatusTransitionEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketStatusChanged event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("transitionId", event.transitionId());
        payload.put("reasonCode", event.reasonCode());
        payload.put("reason", event.reason());
        payload.put("waitingForRequesterSince", event.waitingForRequesterSince() == null ? null : event.waitingForRequesterSince().toString());
        payload.put("approvalReference", event.approvalReference());

        return build("ticket.status.changed", "ticket.status-changed.v1", event.ticketId().toString(), event.aggregateVersion(),
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
