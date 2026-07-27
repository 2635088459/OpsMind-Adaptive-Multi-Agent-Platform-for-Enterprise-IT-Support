package dev.opsmind.ticketworkflow.ticket.application.query;

import java.util.List;

public record RequesterTicketListResult(
    List<RequesterTicketSummary> items,
    int limit,
    boolean hasMore,
    String nextCursor,
    TicketListFilters appliedFilters
) {
}
