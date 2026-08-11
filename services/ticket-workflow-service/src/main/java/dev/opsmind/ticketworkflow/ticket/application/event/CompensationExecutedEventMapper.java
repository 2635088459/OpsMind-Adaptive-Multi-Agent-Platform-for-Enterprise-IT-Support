package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.CompensationRecord;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-040 event-contract: maps an {@code APPLIED} {@link
 * CompensationRecord} to a {@code ticket.compensation-executed.v1} Outbox
 * entry. Mirrors {@code ReconciliationCaseOpenedEventMapper} (SPEC-TW-037):
 * event-contract §"Rules" "Payload is redacted" excludes the free-text
 * {@code reason} — only the opaque {@code recoveryId}/{@code
 * sourceReference}, the low-cardinality {@code decision}/{@code
 * reasonCode}, and the selected {@code compensationAction} (domain-rules
 * "must select a defined action" — itself a fixed, low-cardinality
 * vocabulary, safe to publish) are included.
 */
@Component
public class CompensationExecutedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String EVENT_TYPE = "ticket.compensation-executed";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.compensation-executed.v1";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(CompensationRecord record, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recoveryId", record.id().toString());
        payload.put("sourceReference", record.sourceReference());
        payload.put("decision", record.decision().name());
        payload.put("reasonCode", record.reasonCode().name());
        payload.put("compensationAction", record.compensationAction().name());

        return new OutboxEventEntry(
            UUID.randomUUID(),
            record.id().toString(),
            EVENT_TYPE,
            EVENT_VERSION,
            ROUTING_KEY,
            AGGREGATE_TYPE,
            record.ticketId().toString(),
            record.attemptNumber(),
            TicketId.of(record.ticketId().value()),
            null,
            traceId,
            correlationId,
            causationId,
            DATA_CLASSIFICATION,
            payload,
            record.createdAt(),
            record.createdAt()
        );
    }
}
