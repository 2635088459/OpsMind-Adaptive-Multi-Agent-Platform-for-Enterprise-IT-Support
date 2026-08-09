package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentResult;

public interface UpdateTicketAssignmentUseCase {

    UpdateTicketAssignmentResult updateAssignment(UpdateTicketAssignmentCommand command);
}
