package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;

public interface TicketHistoryWriter {

    void appendInitial(TicketStatusHistoryEntry entry);
}
