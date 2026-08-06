package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Optional;

public interface TicketVerificationSuccessGuardPort {

    /** {@code verificationId} is the {@code ticket_verification_attempts} primary key — globally unique, so no ticket scoping is needed at the query level; the Application layer still cross-checks the returned {@code ticketId} against the event's own to catch a cross-ticket anomaly. */
    Optional<TicketVerificationAttemptGuard> loadGuard(String verificationId);
}
