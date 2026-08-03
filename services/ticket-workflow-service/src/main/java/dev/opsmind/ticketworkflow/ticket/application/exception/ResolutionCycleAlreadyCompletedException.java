package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when the ticket's current resolution cycle is no longer {@code
 * ACTIVE} (SPEC-TW-010 domain-rules §6, AC-06).
 */
public class ResolutionCycleAlreadyCompletedException extends RuntimeException {

    public ResolutionCycleAlreadyCompletedException() {
        super("the current resolution cycle is already completed");
    }
}
