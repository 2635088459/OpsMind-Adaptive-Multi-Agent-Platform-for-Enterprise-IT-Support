package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * SPEC-TW-030 domain-rules: {@code mutable non-terminal state -> same
 * lifecycle state} (transitionId {@code SM-039}, reasonCode {@code
 * TICKET_ASSIGNMENT_UPDATED}) — a pure ownership mutation (team, support
 * queue, and/or assignee) triggered by a support lead, an automated
 * router, or an assignment policy. Distinct from {@link TicketAssigned}/
 * {@link TicketReassigned}/{@link TicketUnassigned} (SPEC-TW-008): those
 * only ever change the assignee within the ticket's existing Support
 * Queue/team (and, for Assign/Unassign, the lifecycle status too); this
 * event additionally carries team/queue moves, and never changes status.
 */
public record TicketAssignmentUpdated(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String previousTeamId,
    String newTeamId,
    SupportQueueId previousSupportQueueId,
    SupportQueueId newSupportQueueId,
    String previousAssigneeId,
    String newAssigneeId,
    String reason,
    String updatedByType,
    String updatedById,
    Instant updatedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketAssignmentUpdated {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(newTeamId, "newTeamId must not be null");
        Objects.requireNonNull(newSupportQueueId, "newSupportQueueId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(updatedByType, "updatedByType must not be null");
        Objects.requireNonNull(updatedById, "updatedById must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
