package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

/**
 * The minimal Ticket projection assignment commands need (mirrors {@link
 * TicketTriageGuard}, SPEC-TW-007 §12). {@code supportQueueId}/{@code
 * teamId} come straight off the ticket row (set once at Triage time, per
 * SPEC-TW-007's deviation #4) rather than a second catalog lookup — both
 * are needed here: {@code teamId} for actor queue-scope authorization
 * (same {@code support_teams} JWT-claim check Triage uses), {@code
 * supportQueueId} for the published event payload.
 */
public record TicketAssignmentGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    SupportQueueId supportQueueId,
    String teamId,
    String currentAssigneeId
) {
}
