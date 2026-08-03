package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketApprovalRequestRepository {

    TicketApprovalRequestUpdateOutcome applyRequestApproval(TicketApprovalRequestUpdate update);
}
