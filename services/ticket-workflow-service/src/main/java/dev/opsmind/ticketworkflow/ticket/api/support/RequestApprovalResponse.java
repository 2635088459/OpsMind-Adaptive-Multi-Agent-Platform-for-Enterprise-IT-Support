package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RequestApprovalResponse(
    UUID ticketId,
    UUID approvalRequestId,
    String approvalId,
    String previousStatus,
    String status,
    String workflowId,
    String actionId,
    String actionType,
    String riskLevel,
    String requestedBy,
    Instant requestedAt,
    long version
) {
}
