package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketApprovalGrantedRepository {

    TicketApprovalGrantedUpdateOutcome applyApprovalGranted(TicketApprovalGrantedUpdate update);
}
