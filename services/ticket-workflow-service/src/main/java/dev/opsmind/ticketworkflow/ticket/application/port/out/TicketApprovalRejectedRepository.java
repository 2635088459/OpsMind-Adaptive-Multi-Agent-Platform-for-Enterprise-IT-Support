package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketApprovalRejectedRepository {

    TicketApprovalRejectedUpdateOutcome applyApprovalRejected(TicketApprovalRejectedUpdate update);
}
