package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;

public interface GetTicketUseCase {

    ConditionalGetResult get(GetTicketQuery query);
}
