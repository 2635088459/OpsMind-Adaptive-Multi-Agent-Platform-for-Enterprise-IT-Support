package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-026 domain-rules: {@code RESOLVED -> CLOSED} (transitionId {@code
 * SM-031}, reasonCode {@code RESOLUTION_CONFIRMED}) — the Phase 08
 * requester/support confirmation path. Distinct from {@link TicketClosed}
 * (SPEC-TW-011's general IT-support closure, transitionId {@code SM-011}):
 * same target state, but a different trigger, a different authorized actor
 * (the ticket's own requester, or a support actor without necessarily
 * holding Support Queue membership), and its own audit/event identity so
 * "the requester confirmed this was fixed" stays distinguishable from an
 * administrative close (auto-close timeout, duplicate, etc.) in the
 * timeline and in downstream consumers.
 */
public record TicketResolutionConfirmed(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
    ResolutionConfirmationReasonCode confirmationReasonCode,
    String reason,
    String confirmedByType,
    String confirmedById,
    Instant confirmedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketResolutionConfirmed {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(confirmationReasonCode, "confirmationReasonCode must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(confirmedByType, "confirmedByType must not be null");
        Objects.requireNonNull(confirmedById, "confirmedById must not be null");
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
