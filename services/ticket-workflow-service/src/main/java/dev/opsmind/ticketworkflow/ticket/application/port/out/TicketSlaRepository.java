package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.model.TicketSlaCycle;

public interface TicketSlaRepository {

    void save(TicketSlaCycle slaCycle);
}
