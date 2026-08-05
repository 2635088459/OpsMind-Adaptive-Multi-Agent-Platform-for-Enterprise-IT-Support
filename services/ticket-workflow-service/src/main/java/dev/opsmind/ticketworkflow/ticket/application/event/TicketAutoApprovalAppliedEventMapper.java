package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoApprovalApplied;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-018 event-contract: maps {@link TicketAutoApprovalApplied} to a {@code ticket.auto-approval-applied.v1} Outbox entry. */
@Component
public class TicketAutoApprovalAppliedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketAutoApprovalApplied event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("ticketId", event.ticketId().value().toString());
        payload.put("approvalRequestId", event.approvalRequestId().toString());
        payload.put("workflowId", event.workflowId());
        payload.put("actionId", event.actionId());
        payload.put("actionType", event.actionType());
        payload.put("riskLevel", event.riskLevel().name());
        payload.put("policyId", event.policyId());
        payload.put("policyVersion", event.policyVersion());
        payload.put("policyDecisionId", event.policyDecisionId());
        payload.put("authorizationReference", event.authorizationReference());
        payload.put("decidedAt", event.decidedAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.auto-approval-applied",
            "1.0",
            "ticket.auto-approval-applied.v1",
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
