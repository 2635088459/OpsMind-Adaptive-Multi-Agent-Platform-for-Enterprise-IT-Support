package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.ReplayEventRecord;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-038 event-contract: maps an {@code APPLIED} {@link
 * ReplayEventRecord} to a {@code ticket.event-replay-recorded.v1} Outbox
 * entry. Mirrors {@code ReconciliationCaseOpenedEventMapper} (SPEC-TW-037):
 * event-contract §"Rules" "Payload is redacted" excludes the free-text
 * {@code reason} — only the opaque {@code recoveryId}/{@code
 * sourceReference} and the low-cardinality {@code decision}/{@code
 * reasonCode} are published.
 */
@Component
public class EventReplayRecordedEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String EVENT_TYPE = "ticket.event-replay-recorded";
    private static final String EVENT_VERSION = "1.0";
    private static final String ROUTING_KEY = "ticket.event-replay-recorded.v1";
    private static final String DATA_CLASSIFICATION = "INTERNAL";

    public OutboxEventEntry map(ReplayEventRecord record, SupportQueueId supportQueueId, String traceId, String correlationId, String causationId) {
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
