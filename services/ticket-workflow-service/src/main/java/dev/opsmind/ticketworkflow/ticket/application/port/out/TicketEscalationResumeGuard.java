package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.util.UUID;

/** SPEC-TW-032: the ticket row projection needed to classify a Resume command. Mirrors {@link TicketEscalationGuard} minus {@code activeWorkflowId} — Resume never touches it. */
public record TicketEscalationResumeGuard(
    TicketId ticketId,
    TicketDisplayId displayId,
    TicketStatus status,
    long version,
    String teamId,
    SupportQueueId supportQueueId,
    String currentAssigneeId,
    UUID currentResolutionCycleId
) {
}
