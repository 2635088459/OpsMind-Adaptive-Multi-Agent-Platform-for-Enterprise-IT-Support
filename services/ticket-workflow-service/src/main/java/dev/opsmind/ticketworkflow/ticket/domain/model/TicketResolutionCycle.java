package dev.opsmind.ticketworkflow.ticket.domain.model;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TicketResolutionCycle(
    UUID resolutionCycleId,
    TicketId ticketId,
    int cycleNumber,
    ResolutionCycleStatus cycleStatus,
    Instant openedAt
) {

    public TicketResolutionCycle {
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(cycleStatus, "cycleStatus must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        if (cycleNumber < 1) {
            throw new IllegalArgumentException("cycleNumber must be >= 1");
        }
    }

    public static TicketResolutionCycle openInitial(UUID resolutionCycleId, TicketId ticketId, Instant openedAt) {
        return new TicketResolutionCycle(resolutionCycleId, ticketId, 1, ResolutionCycleStatus.ACTIVE, openedAt);
    }
}
