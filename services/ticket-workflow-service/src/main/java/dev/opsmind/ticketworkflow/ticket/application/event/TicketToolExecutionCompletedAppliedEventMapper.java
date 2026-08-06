package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionCompletedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-019 event-contract: maps {@link TicketToolExecutionCompletedApplied} to a {@code ticket.tool-execution-completed-applied.v1} Outbox entry. */
@Component
public class TicketToolExecutionCompletedAppliedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketToolExecutionCompletedApplied event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId == null ? null : supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("ticketId", event.ticketId().value().toString());
        payload.put("workflowId", event.workflowId());
        payload.put("actionId", event.actionId());
        payload.put("authorizationReference", event.authorizationReference());
        payload.put("toolExecutionId", event.toolExecutionId());
        payload.put("toolResultId", event.toolResultId());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("completedAt", event.completedAt().toString());
        payload.put("resultSummary", event.resultSummary());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.tool-execution-completed-applied",
            "1.0",
            "ticket.tool-execution-completed-applied.v1",
            AGGREGATE_TYPE,
            event.ticketId().toString(),
            event.aggregateVersion(),
            TicketId.of(event.ticketId().value()),
            event.workflowId(),
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
