package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * SPEC-TW-030: routes a ticket to (possibly) a new team/Support Queue
 * and/or assignee. Unlike {@link AssignTicketCommand}/{@link
 * ReassignTicketCommand} (SPEC-TW-008), there is no {@code
 * allowedTeamIds} — this command's actor (support lead, router, or
 * assignment policy) is never queue-scoped.
 */
public record UpdateTicketAssignmentCommand(
    TicketId ticketId,
    SupportQueueId supportQueueId,
    String assigneeId,
    String reason,
    long expectedVersion,
    ActorContext actor,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {
}
