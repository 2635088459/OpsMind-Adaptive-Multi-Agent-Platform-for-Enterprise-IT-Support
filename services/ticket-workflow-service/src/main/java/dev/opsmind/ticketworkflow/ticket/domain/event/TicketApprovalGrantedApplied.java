package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** SPEC-TW-015 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS} (transitionId {@code SM-017}, reasonCode {@code APPROVAL_GRANTED}). */
public record TicketApprovalGrantedApplied(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID approvalRequestId,
    String approvalId,
    String workflowId,
    String actionId,
    String actionType,
    String approvedBy,
    Instant approvedAt,
    String authorizationReference,
    String grantedEventId,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketApprovalGrantedApplied {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(grantedEventId, "grantedEventId must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
