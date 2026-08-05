package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-018 domain-rules §1: {@code IN_PROGRESS -> IN_PROGRESS} (transitionId
 * {@code SM-020}, reasonCode {@code AUTO_APPROVAL_APPLIED}). Unlike SPEC-TW-015/
 * 016/017, the ticket status does not change — auto-approval means the
 * policy engine decided upfront that this action never needs to pause the
 * ticket into {@code WAITING_FOR_APPROVAL} at all. The self-transition is
 * still a real recorded event (its own status-history row and aggregate
 * version bump), not a no-op, per domain-rules' explicit {@code SM-020}
 * entry.
 */
public record TicketAutoApprovalApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID approvalRequestId,
    String workflowId,
    String actionId,
    String actionType,
    ApprovalRiskLevel riskLevel,
    String policyId,
    String policyVersion,
    String policyDecisionId,
    String authorizationReference,
    Instant decidedAt,
    String autoApprovalEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketAutoApprovalApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(policyDecisionId, "policyDecisionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(autoApprovalEventId, "autoApprovalEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
