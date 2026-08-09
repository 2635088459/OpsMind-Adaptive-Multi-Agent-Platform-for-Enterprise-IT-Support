package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketResult;

public interface ResumeEscalatedTicketUseCase {

    ResumeEscalatedTicketResult resume(ResumeEscalatedTicketCommand command);
}
