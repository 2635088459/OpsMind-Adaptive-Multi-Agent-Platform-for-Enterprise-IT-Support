package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/**
 * SPEC-TW-038: the ticket the original event (identified by {@code
 * sourceReference}) belongs to, resolved by joining {@code
 * ticket.outbox_events} with {@code ticket.tickets} — mirrors {@code
 * TicketReconciliationGuard} (SPEC-TW-037): no {@code status} or {@code
 * version}, since replaying an event never inspects or depends on the
 * ticket's current lifecycle state.
 */
public record ReplayEventGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    SupportQueueId supportQueueId
) {
}
