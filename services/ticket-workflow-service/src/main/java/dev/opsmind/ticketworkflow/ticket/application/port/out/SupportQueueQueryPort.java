package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Query-side port for the Support Queue (SPEC-TW-005 §19). Application
 * scope, every filter, the fixed {@code evaluationTime}, and the keyset
 * boundary are all pushed into the implementation's SQL predicate — never
 * post-filtered in Java. Returns up to {@code limitPlusOne} rows ordered
 * {@code slaRank ASC, priorityRank ASC, createdAt ASC, ticketId ASC} so
 * the caller can compute {@code hasMore} without a separate count query.
 */
public interface SupportQueueQueryPort {

    /**
     * @param cursorPosition keyset boundary from the previous page, or {@code null} for the first page
     */
    List<SupportTicketSummary> queryQueue(
        SupportQueueScope scope,
        SupportQueueFilters filters,
        Instant evaluationTime,
        Duration atRiskWindow,
        SupportQueueKeysetPosition cursorPosition,
        int limitPlusOne
    );
}
