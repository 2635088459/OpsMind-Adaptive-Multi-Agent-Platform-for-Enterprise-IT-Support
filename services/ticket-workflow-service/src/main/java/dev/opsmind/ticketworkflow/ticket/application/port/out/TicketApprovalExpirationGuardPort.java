package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

public interface TicketApprovalExpirationGuardPort {

    Optional<TicketApprovalExpirationGuard> loadGuard(TicketId ticketId, String approvalId);
}
