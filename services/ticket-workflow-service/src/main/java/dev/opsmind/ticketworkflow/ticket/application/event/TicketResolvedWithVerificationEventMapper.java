package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolvedWithVerification;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SPEC-TW-025 event-contract §3: maps {@link TicketResolvedWithVerification} to a {@code ticket.resolved-with-verification.v1} Outbox entry. */
@Component
public class TicketResolvedWithVerificationEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(TicketResolvedWithVerification event, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supportQueueId", supportQueueId == null ? null : supportQueueId.toString());
        payload.put("assigneeId", event.assigneeId());
        payload.put("resolutionCycleId", event.resolutionCycleId().toString());
        payload.put("verificationId", event.verificationId());
        payload.put("verificationEvidenceId", event.verificationEvidenceId());
        payload.put("previousStatus", event.previousStatus().name());
        payload.put("newStatus", event.newStatus().name());
        payload.put("resolutionCode", event.resolutionCode().name());
        payload.put("resolutionSummary", event.resolutionSummary());
        payload.put("resolvedBy", event.resolvedById());
        payload.put("resolvedAt", event.resolvedAt().toString());
        payload.put("autoCloseDueAt", event.autoCloseDueAt().toString());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "ticket.resolved-with-verification",
            "1.0",
            "ticket.resolved-with-verification.v1",
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
