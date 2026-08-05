package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketApprovalExpiredRepository {

    TicketApprovalExpiredUpdateOutcome applyApprovalExpired(TicketApprovalExpiredUpdate update);
}
