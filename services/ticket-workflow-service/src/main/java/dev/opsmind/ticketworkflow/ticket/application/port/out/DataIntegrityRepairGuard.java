package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/**
 * SPEC-TW-041: the ticket the reconciliation case (identified by {@code
 * sourceReference}) belongs to, resolved by joining {@code
 * ticket.ticket_phase10_open_reconciliation_case} with {@code
 * ticket.tickets}. Mirrors {@code ReplayEventGuard} (SPEC-TW-038): no
 * {@code status} or {@code version}, since applying a repair never inspects
 * or depends on the ticket's current lifecycle state.
 */
public record DataIntegrityRepairGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    SupportQueueId supportQueueId
) {
}
