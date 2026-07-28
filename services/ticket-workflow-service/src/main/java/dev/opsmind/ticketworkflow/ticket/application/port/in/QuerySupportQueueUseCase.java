package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;

public interface QuerySupportQueueUseCase {

    SupportQueueResult query(SupportQueueQuery query);
}
