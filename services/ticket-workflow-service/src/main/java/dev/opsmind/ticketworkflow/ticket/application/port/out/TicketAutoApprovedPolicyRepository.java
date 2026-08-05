package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketAutoApprovedPolicyRepository {

    TicketAutoApprovedPolicyInsertOutcome applyAutoApprovedPolicy(TicketAutoApprovedPolicyInsert insert);
}
