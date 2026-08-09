package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record ConfirmResolutionResult(
    TicketId ticketId,
    TicketStatus previousStatus,
    TicketStatus status,
    ResolutionConfirmationReasonCode reasonCode,
    String confirmedBy,
    Instant confirmedAt,
    UUID resolutionCycleId,
    long version,
    boolean replayed
) {
}
