package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 06-event-contracts CON-008 {@code approval.expired} payload shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalExpiredEventPayload(
    String workflowId,
    String actionId,
    String approvalId,
    Instant expiredAt
) {
}
