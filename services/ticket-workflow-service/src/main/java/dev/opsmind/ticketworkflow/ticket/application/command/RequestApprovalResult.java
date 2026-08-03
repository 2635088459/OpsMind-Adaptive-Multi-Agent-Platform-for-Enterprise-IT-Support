package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record RequestApprovalResult(
    TicketId ticketId,
    UUID approvalRequestId,
    String approvalId,
    TicketStatus previousStatus,
    TicketStatus status,
    String workflowId,
    String actionId,
    String actionType,
    ApprovalRiskLevel riskLevel,
    String requestedBy,
    Instant requestedAt,
    long version,
    boolean replayed
) {
}
