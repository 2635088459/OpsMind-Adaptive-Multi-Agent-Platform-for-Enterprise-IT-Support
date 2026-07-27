package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;

public interface ListRequesterTicketsUseCase {

    RequesterTicketListResult list(ListRequesterTicketsQuery query);
}
