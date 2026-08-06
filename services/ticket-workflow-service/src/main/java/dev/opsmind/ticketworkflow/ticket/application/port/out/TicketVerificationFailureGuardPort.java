package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Optional;

/**
 * SPEC-TW-024: same projection shape as {@link TicketVerificationSuccessGuardPort}
 * (SPEC-TW-023) — kept as its own port/adapter so each spec has exactly
 * one implementing bean, per this codebase's established one-port-per-spec
 * convention.
 */
public interface TicketVerificationFailureGuardPort {

    Optional<TicketVerificationAttemptGuard> loadGuard(String verificationId);
}
