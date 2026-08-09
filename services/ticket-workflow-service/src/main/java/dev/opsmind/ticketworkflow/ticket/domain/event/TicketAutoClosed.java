package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-TW-027 domain-rules: {@code RESOLVED -> CLOSED} (transitionId {@code
 * SM-032}, reasonCode {@code TICKET_AUTO_CLOSED}) — the Phase 08
 * scheduler-driven auto-close path. Distinct from {@link TicketClosed}
 * (SPEC-TW-011) and {@link TicketResolutionConfirmed} (SPEC-TW-026): same
 * target state, but {@link #closeReasonCode()} is always {@link
 * CloseReasonCode#AUTO_CLOSE_TIMEOUT} — {@code
 * Ticket.autoClose(...)} is the only place that ever constructs this event,
 * and it always supplies that literal — a scheduler policy worker, not a
 * person, closes the ticket once its {@code auto_close_due_at}
 * (SPEC-TW-010) has passed without confirmation, rejection, or reopen.
 */
public record TicketAutoClosed(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID resolutionCycleId,
    CloseReasonCode closeReasonCode,
    String reason,
    String closedByType,
    String closedById,
    Instant closedAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketAutoClosed {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(closeReasonCode, "closeReasonCode must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(closedByType, "closedByType must not be null");
        Objects.requireNonNull(closedById, "closedById must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
