package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;

public interface TicketAssignmentHistoryWriter {

    void append(TicketAssignmentHistoryEntry entry);
}
