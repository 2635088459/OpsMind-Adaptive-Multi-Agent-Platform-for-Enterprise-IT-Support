package dev.opsmind.ticketworkflow.ticket.domain.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.SlaStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TicketSlaCycle(
    UUID slaCycleId,
    TicketId ticketId,
    UUID resolutionCycleId,
    String policyId,
    int cycleNumber,
    SlaStatus status,
    Instant responseDueAt,
    Instant resolutionDueAt,
    long accumulatedPausedSeconds,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    public TicketSlaCycle {
        Objects.requireNonNull(slaCycleId, "slaCycleId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (cycleNumber < 1) {
            throw new IllegalArgumentException("cycleNumber must be >= 1");
        }
        if (resolutionDueAt != null && resolutionDueAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("resolutionDueAt must not precede createdAt");
        }
    }

    public static TicketSlaCycle openInitial(
        UUID slaCycleId,
        TicketId ticketId,
        UUID resolutionCycleId,
        String policyId,
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant now
    ) {
        return new TicketSlaCycle(
            slaCycleId,
            ticketId,
            resolutionCycleId,
            policyId,
            1,
            SlaStatus.ACTIVE,
            responseDueAt,
            resolutionDueAt,
            0L,
            now,
            now,
            0L
        );
    }
}
