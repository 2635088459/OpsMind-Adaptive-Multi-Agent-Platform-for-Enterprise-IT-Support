package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolResultUnknownRecorded;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-021 event-contract: maps {@link TicketToolResultUnknownRecorded} to a {@code ticket.tool-result-unknown-recorded.v1} Outbox entry. */
@Component
public class TicketToolResultUnknownRecordedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketToolResultUnknownRecorded event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId == null ? null : supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("ticketId", event.ticketId().value().toString());
        payload.put("workflowId", event.workflowId());
        payload.put("actionId", event.actionId());
        payload.put("authorizationReference", event.authorizationReference());
        payload.put("toolExecutionId", event.toolExecutionId());
        payload.put("unknownReason", event.unknownReason());
        payload.put("evidenceReferences", event.evidenceReferences());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("observedAt", event.observedAt().toString());
        payload.put("reconciliationRequired", event.reconciliationRequired());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.tool-result-unknown-recorded",
            "1.0",
            "ticket.tool-result-unknown-recorded.v1",
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
