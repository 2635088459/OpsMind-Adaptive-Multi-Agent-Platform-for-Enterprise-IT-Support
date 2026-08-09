package dev.opsmind.ticketworkflow.ticket.application.port.out;

public interface TicketEscalationResumeRepository {

    TicketEscalationResumeUpdateOutcome applyResume(TicketEscalationResumeUpdate update);
}
