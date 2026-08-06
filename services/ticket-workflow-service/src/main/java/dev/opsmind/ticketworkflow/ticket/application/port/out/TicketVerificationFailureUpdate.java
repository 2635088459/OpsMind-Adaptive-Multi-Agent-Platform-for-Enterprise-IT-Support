package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;

public record TicketVerificationFailureUpdate(
    TicketId ticketId,
    long expectedVersion,
    TicketStatus newStatus,
    String verificationId,
    String failureCode,
    String failureClass,
    boolean unsafeResult,
    Instant failedAt,
    String failedEventId,
    Instant updatedAt
) {
}
