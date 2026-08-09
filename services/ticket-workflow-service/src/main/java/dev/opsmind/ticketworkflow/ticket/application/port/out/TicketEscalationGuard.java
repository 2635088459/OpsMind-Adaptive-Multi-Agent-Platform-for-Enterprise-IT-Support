package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/**
 * SPEC-TW-031: the ticket row projection needed to classify an Escalate
 * command. Unlike {@link TicketCancelGuard}, {@code activeWorkflowId} is
 * carried too — {@code Ticket.escalate(...)} threads it straight through
 * onto {@code TicketEscalated} (domain-rules: "must preserve the current
 * work context"), and {@code teamId} is needed for the published event
 * payload the same way {@link TicketAssignmentGuard} needs it.
 */
public record TicketEscalationGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    String teamId,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId,
    String activeWorkflowId
) {
}
