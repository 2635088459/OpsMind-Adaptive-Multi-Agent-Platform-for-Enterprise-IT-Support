package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;

public interface AddTicketMessageUseCase {

    AddTicketMessageResult addMessage(AddTicketMessageCommand command);
}
