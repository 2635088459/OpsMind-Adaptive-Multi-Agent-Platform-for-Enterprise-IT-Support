package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketUserReplyRepository {

    TicketUserReplyResumeUpdateOutcome applyResume(TicketUserReplyResumeUpdate update);
}
