package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-023: the ticket + verification-attempt projection needed to
 * classify an inbound {@code verification.completed.v1} event before
 * applying it — joins {@code ticket_verification_attempts} (SPEC-TW-022)
 * with its ticket so the Application layer can validate workflow,
 * resolution cycle, attempt number, and current-cycle freshness without a
 * second round trip.
 */
public record TicketVerificationAttemptGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus ticketStatus,
    long ticketVersion,
    SupportQueueId supportQueueId,
    String assigneeId,
    String attemptWorkflowId,
    UUID attemptResolutionCycleId,
    int attemptNumber,
    String attemptStatus,
    UUID currentResolutionCycleId
) {
}
