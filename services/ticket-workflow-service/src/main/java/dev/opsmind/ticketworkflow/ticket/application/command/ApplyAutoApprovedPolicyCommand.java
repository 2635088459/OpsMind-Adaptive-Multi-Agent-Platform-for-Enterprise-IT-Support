package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/** Mapped from a trusted, schema- and producer-validated {@code policy.action-auto-approved.v1} event by the messaging infrastructure layer. */
public record ApplyAutoApprovedPolicyCommand(
    TicketId ticketId,
    String eventId,
    String workflowId,
    String actionId,
    String actionType,
    ApprovalRiskLevel riskLevel,
    String policyId,
    String policyVersion,
    String policyDecisionId,
    Instant decidedAt,
    String traceId,
    String correlationId
) {
}
