package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Query-side port for List Requester Tickets (SPEC-TW-003 §12).
 * Requester ownership and every filter are pushed into the implementation's
 * SQL predicate. Returns up to {@code limitPlusOne} rows, sorted
 * {@code createdAt DESC, ticketId DESC}, so the caller can compute
 * {@code hasMore} without a separate count query.
 */
public interface RequesterTicketQueryPort {

    /**
     * @param cursorLastCreatedAt keyset boundary from the previous page, or {@code null} for the first page
     * @param cursorLastTicketId  keyset tie-breaker from the previous page, or {@code null} for the first page
     */
    List<RequesterTicketSummary> listForRequester(
        String requesterId,
        TicketListFilters filters,
        Instant cursorLastCreatedAt,
        UUID cursorLastTicketId,
        int limitPlusOne
    );
}
