package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 06-event-contracts CON-006 {@code approval.granted} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalGrantedEventPayload(
    String workflowId,
    String actionId,
    String actionType,
    String approvalId,
    String approvedByIdHash,
    Instant approvedAt,
    Instant expiresAt,
    String toolExecutionId
) {
}
