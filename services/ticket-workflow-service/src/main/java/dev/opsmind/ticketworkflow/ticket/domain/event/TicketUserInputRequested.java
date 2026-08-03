package dev.opsmind.ticketworkflow.ticket.domain.event;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** SPEC-TW-012 §1: {@code IN_PROGRESS -> WAITING_FOR_USER} (transitionId {@code SM-014}, reasonCode {@code USER_INPUT_REQUIRED}). */
public record TicketUserInputRequested(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus newStatus,
    String assigneeId,
    UUID requestId,
    String prompt,
    List<String> requestedFields,
    String requestedByType,
    String requestedById,
    Instant requestedAt,
    Instant waitingForRequesterSince,
    String resumeStatus,
    Instant expiresAt,
    String transitionId,
    String reasonCode,
    long aggregateVersion,
    Instant occurredAt
) implements TicketDomainEvent {

    public TicketUserInputRequested {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        requestedFields = requestedFields == null ? List.of() : List.copyOf(requestedFields);
        Objects.requireNonNull(requestedByType, "requestedByType must not be null");
        Objects.requireNonNull(requestedById, "requestedById must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(waitingForRequesterSince, "waitingForRequesterSince must not be null");
        Objects.requireNonNull(resumeStatus, "resumeStatus must not be null");
        Objects.requireNonNull(transitionId, "transitionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
