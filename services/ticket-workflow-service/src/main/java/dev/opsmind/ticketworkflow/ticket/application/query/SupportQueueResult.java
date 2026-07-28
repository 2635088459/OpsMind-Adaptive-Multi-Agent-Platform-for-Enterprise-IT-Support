package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.List;

public record SupportQueueResult(
    List<SupportTicketSummary> items,
    int limit,
    boolean hasMore,
    String nextCursor,
    Instant evaluationTime,
    SupportQueueFilters appliedFilters
) {
}
