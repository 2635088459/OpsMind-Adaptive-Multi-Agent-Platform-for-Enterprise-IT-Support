package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.model.TicketResolutionCycle;

public interface TicketResolutionCycleRepository {

    void save(TicketResolutionCycle cycle);
}
