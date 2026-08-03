package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when {@code Ticket.reassign(...)} is invoked with the ticket's
 * current assignee (SPEC-TW-008 domain-rules §4 point 3, API contract:
 * "the new assignee must differ from the current assignee"). Maps to
 * {@code 400 VALIDATION_ERROR} — the request itself doesn't describe a
 * valid reassignment, not a state or eligibility conflict.
 */
public class ReassignmentRequiresDifferentAssigneeException extends RuntimeException {

    public ReassignmentRequiresDifferentAssigneeException() {
        super("the new assignee must differ from the current assignee");
    }
}
