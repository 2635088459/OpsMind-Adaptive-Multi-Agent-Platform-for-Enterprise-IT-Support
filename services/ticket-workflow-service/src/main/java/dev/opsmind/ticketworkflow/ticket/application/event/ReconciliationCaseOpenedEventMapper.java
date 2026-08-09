package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.ReconciliationCaseRecord;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-037 event-contract: maps an {@code APPLIED} {@link
 * ReconciliationCaseRecord} to a {@code ticket.reconciliation-case-opened.v1}
 * Outbox entry. event-contract §"Rules": "Payload is redacted" — deliberately
 * excludes the free-text {@code reason} (acceptance-criteria "Audit payloads
 * contain no secrets, tokens, or high-cardinality fields"); only the opaque
 * {@code recoveryId}/{@code sourceReference} and the low-cardinality {@code
 * decision}/{@code reasonCode} are published.
 */
@Component
public class ReconciliationCaseOpenedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String EVENT_TYPE = "ticket.reconciliation-case-opened";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.reconciliation-case-opened.v1";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(ReconciliationCaseRecord record, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recoveryId", record.id().toString());
        payload.put("sourceReference", record.sourceReference());
        payload.put("decision", record.decision().name());
        payload.put("reasonCode", record.reasonCode().name());

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
