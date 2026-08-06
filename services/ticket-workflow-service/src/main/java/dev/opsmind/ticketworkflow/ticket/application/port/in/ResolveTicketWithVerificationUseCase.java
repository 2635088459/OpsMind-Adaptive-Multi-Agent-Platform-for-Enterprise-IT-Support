package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationResult;

public interface ResolveTicketWithVerificationUseCase {

    ResolveTicketWithVerificationResult resolveWithVerification(ResolveTicketWithVerificationCommand command);
}
