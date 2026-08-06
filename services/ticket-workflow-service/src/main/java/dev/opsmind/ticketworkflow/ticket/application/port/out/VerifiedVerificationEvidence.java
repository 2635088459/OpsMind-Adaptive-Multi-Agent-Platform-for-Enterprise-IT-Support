package dev.opsmind.ticketworkflow.ticket.application.port.out;

/**
 * SPEC-TW-025 domain-rules §"evidence matches current workflow/cycle/attempt":
 * the {@code ticket_verification_attempts} (SPEC-TW-022) row identity that a
 * {@code SUCCEEDED} evidence lookup, already scoped to the ticket's current
 * resolution cycle, resolves to — everything {@code
 * Ticket.resolveWithVerification(...)} needs to trace the resolution back to
 * exactly which verification attempt justified it.
 */
public record VerifiedVerificationEvidence(
    String verificationId,
    String workflowId,
    int attemptNumber
) {
}
