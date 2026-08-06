package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationStarted;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-022 event-contract: maps {@link TicketVerificationStarted} to a {@code ticket.verification-started.v1} Outbox entry. */
@Component
public class TicketVerificationStartedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketVerificationStarted event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId == null ? null : supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("ticketId", event.ticketId().value().toString());
        payload.put("verificationId", event.verificationId());
        payload.put("toolResultId", event.toolResultId());
        payload.put("workflowId", event.workflowId());
        payload.put("resolutionCycleId", event.resolutionCycleId().toString());
        payload.put("attemptNumber", event.attemptNumber());
        payload.put("verificationType", event.verificationType());
        payload.put("startedAt", event.startedAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.verification-started",
            "1.0",
            "ticket.verification-started.v1",
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
