package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

public interface TicketCompensationGuardPort {

    Optional<TicketCompensationGuard> loadGuard(TicketId ticketId);
}
