package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;

public interface TicketMessageRepository {

    void save(TicketMessage message);
}
