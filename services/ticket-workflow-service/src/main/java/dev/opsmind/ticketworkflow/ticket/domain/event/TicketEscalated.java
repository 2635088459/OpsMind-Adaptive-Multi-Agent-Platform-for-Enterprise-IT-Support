package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-031 domain-rules: mutable non-terminal state -> ESCALATED.
 * Escalation is a governance freeze, not a failure result.
 */
public record TicketEscalated(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String teamId,
    SupportQueueId supportQueueId,
    String assigneeId,
    UUID resolutionCycleId,
    String workflowId,
    EscalationReasonCode escalationReasonCode,
    String escalationReason,
    String escalatedByType,
    String escalatedById,
    Instant escalatedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketEscalated {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(escalationReasonCode, "escalationReasonCode must not be null");
        Objects.requireNonNull(escalationReason, "escalationReason must not be null");
        Objects.requireNonNull(escalatedByType, "escalatedByType must not be null");
        Objects.requireNonNull(escalatedById, "escalatedById must not be null");
        Objects.requireNonNull(escalatedAt, "escalatedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
