package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 06-event-contracts CON-007 {@code approval.rejected} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalRejectedEventPayload(
    String workflowId,
    String actionId,
    String approvalId,
    String reasonCode,
    String rejectedByIdHash,
    Instant rejectedAt
) {
}
