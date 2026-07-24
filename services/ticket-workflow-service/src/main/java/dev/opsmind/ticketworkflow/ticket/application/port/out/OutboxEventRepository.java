package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;

public interface OutboxEventRepository {

    void append(OutboxEventEntry entry);
}
