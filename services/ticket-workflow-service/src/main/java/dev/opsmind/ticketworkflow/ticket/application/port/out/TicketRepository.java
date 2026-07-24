package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;

public interface TicketRepository {

    /**
     * Inserts a new ticket. Throws
     * {@link dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdCollisionException}
     * if the display id is already in use.
     */
    void save(Ticket ticket);
}
