package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/**
 * SPEC-TW-040: the minimal ticket projection this SPEC needs. Mirrors
 * {@code TicketReconciliationGuard} (SPEC-TW-037): no {@code status} or
 * {@code version}, since executing a compensation never inspects or depends
 * on the ticket's current lifecycle state — it only realigns external side
 * effects (README §"Goal").
 */
public record TicketCompensationGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    SupportQueueId supportQueueId
) {
}
