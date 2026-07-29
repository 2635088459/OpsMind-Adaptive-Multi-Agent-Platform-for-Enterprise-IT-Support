package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;

public interface GetTicketTimelineUseCase {

    TicketTimelineResult getTimeline(TicketTimelineQuery query);
}
