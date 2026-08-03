package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a ticket is already {@code WAITING_FOR_APPROVAL} (it therefore
 * already has an {@code OPEN} approval request) and a new Request Approval
 * command targets it (SPEC-TW-014 acceptance-criteria). Distinct from the
 * generic {@link dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException}
 * so the client sees the more specific stable error code.
 */
public class ApprovalRequestAlreadyOpenException extends RuntimeException {

    public ApprovalRequestAlreadyOpenException() {
        super("the ticket already has an open approval request");
    }
}
