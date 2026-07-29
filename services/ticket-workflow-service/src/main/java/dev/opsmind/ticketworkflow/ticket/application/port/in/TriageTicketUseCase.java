package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketResult;

public interface TriageTicketUseCase {

    TriageTicketResult triage(TriageTicketCommand command);
}
