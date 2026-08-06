package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/** SPEC-TW-022: the ticket projection needed to classify a Start Verification command before applying it. Unlike {@link TicketStatusTransitionGuard} (SPEC-TW-009), this also carries {@code currentResolutionCycleId} — the resolution cycle a new verification attempt binds to. */
public record TicketVerificationStartGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId
) {
}
