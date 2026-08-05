package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TicketAutoApprovedPolicyInsert(
    TicketId ticketId,
    long expectedVersion,
    UUID approvalRequestId,
    String policyDecisionId,
    String workflowId,
    String actionId,
    String actionType,
    String riskLevel,
    Map<String, Object> riskContext,
    String policyId,
    String policyVersion,
    String authorizationReference,
    Instant decidedAt,
    String autoApprovalEventId,
    Instant updatedAt
) {
}
