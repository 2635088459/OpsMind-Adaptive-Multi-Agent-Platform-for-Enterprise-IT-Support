package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * resolutionCycleId: the initial resolution cycle CreateTicketApplicationService
 * already opens for every new Ticket (TicketResolutionCycle.openInitial()) — added so
 * a synchronous caller (agent-runtime-service's SPEC-ARO-038, which must bind a
 * WorkflowInstance to ticketId + ticketCycleId within this same request, before any
 * async ticket.created.v1 delivery could otherwise carry it) does not have to wait on
 * that event.
 */
public record CreateTicketResult(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    Instant createdAt,
    long version,
    UUID resolutionCycleId,
    boolean idempotencyReplayed
) {
}
