package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when an auto-close command reaches {@code Ticket.autoClose(...)}
 * before the ticket's {@code auto_close_due_at} has actually passed
 * (SPEC-TW-027 domain-rules: "the scheduler signal is advisory; the service
 * recomputes eligibility under lock" — the scheduler's own timing is never
 * trusted, only the ticket row's own due date is authoritative).
 */
public class AutoCloseNotYetDueException extends RuntimeException {

    public AutoCloseNotYetDueException() {
        super("the ticket's auto-close due date has not yet passed");
    }
}
