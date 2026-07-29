package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;

public interface TicketHistoryWriter {

    /**
     * Appends any transition (SPEC-TW-007 §10) — the general-purpose form
     * {@link #appendInitial(TicketStatusHistoryEntry)} now delegates to.
     */
    void append(TicketStatusHistoryEntry entry);

    default void appendInitial(TicketStatusHistoryEntry entry) {
        append(entry);
    }
}
