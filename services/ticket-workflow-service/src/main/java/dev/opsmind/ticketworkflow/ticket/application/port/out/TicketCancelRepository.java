package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketCancelRepository {

    TicketCancelUpdateOutcome applyCancel(TicketCancelUpdate update);
}
