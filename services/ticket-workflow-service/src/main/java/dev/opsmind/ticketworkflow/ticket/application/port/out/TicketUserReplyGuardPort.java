package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;
import java.util.UUID;

public interface TicketUserReplyGuardPort {

    Optional<TicketUserReplyGuard> loadGuard(TicketId ticketId, UUID requestId);
}
