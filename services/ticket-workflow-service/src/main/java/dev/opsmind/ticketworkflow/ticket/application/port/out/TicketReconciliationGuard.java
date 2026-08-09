package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/**
 * SPEC-TW-037: the minimal ticket projection this SPEC needs. Unlike {@code
 * TicketAutoCloseGuard} (SPEC-TW-027) or other lifecycle guards, this one
 * carries no {@code status} or {@code version} — domain-rules: "A
 * reconciliation case is the recovery entry point and must not directly
 * repair business state," so opening one never inspects or depends on the
 * ticket's current status/version, only that the ticket exists.
 */
public record TicketReconciliationGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    SupportQueueId supportQueueId
) {
}
