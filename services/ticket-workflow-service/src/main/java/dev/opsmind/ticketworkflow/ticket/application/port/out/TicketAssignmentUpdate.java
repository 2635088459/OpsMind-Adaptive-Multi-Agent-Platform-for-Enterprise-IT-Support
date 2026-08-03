package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Set;

/**
 * The version/state-guarded {@code UPDATE}'s parameters (mirrors {@link
 * TicketTriageUpdate}, SPEC-TW-007). {@code requiredCurrentStatuses} is a
 * set rather than a single value so the same adapter method serves Assign
 * (single required status {@code TRIAGED}), Reassign ({@link
 * dev.opsmind.ticketworkflow.ticket.domain.model.Ticket#REASSIGNABLE_STATUSES}),
 * and Unassign (single required status {@code ASSIGNED}).
 * {@code newAssigneeId}/{@code assignedAt}/{@code assignedBy} are all
 * {@code null} for Unassign.
 */
public record TicketAssignmentUpdate(
    TicketId ticketId,
    long expectedVersion,
    Set<TicketStatus> requiredCurrentStatuses,
    TicketStatus newStatus,
    String newAssigneeId,
    Instant assignedAt,
    String assignedBy,
    Instant updatedAt
) {
}
