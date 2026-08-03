package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** SPEC-TW-014 §1: {@code IN_PROGRESS -> WAITING_FOR_APPROVAL} (transitionId {@code SM-016}, reasonCode {@code APPROVAL_REQUIRED}). */
public record TicketApprovalWaitStarted(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID approvalRequestId,
    String approvalId,
    String workflowId,
    String actionId,
    String actionType,
    ApprovalRiskLevel riskLevel,
    Map<String, Object> riskContext,
    String reason,
    String requestedByType,
    String requestedById,
    Instant requestedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketApprovalWaitStarted {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        riskContext = riskContext == null ? Map.of() : Map.copyOf(riskContext);
        Objects.requireNonNull(requestedByType, "requestedByType must not be null");
        Objects.requireNonNull(requestedById, "requestedById must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
