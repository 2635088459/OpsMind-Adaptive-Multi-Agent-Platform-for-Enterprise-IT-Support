package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;

public interface TicketDisplayIdGenerator {

    TicketDisplayId generate();
}
