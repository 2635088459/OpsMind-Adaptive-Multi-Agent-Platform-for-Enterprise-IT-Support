package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/**
 * SPEC-TW-039: the minimal ticket projection this SPEC needs. Mirrors {@code
 * TicketReconciliationGuard} (SPEC-TW-037): no {@code status} or {@code
 * version}, since publishing a correction event never inspects or depends
 * on the ticket's current lifecycle state (domain-rules: "Correction events
 * must not delete or rewrite original events").
 */
public record TicketCorrectionEventGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    SupportQueueId supportQueueId
) {
}
