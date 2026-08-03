package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when the ticket's {@code current_resolution_cycle_id} does not
 * point at an existing resolution-cycle row (SPEC-TW-010 domain-rules §6,
 * AC-06).
 */
public class ResolutionCycleNotFoundException extends RuntimeException {

    public ResolutionCycleNotFoundException() {
        super("the ticket has no current resolution cycle");
    }
}
