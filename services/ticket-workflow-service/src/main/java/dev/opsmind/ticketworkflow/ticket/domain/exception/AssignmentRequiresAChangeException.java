package dev.opsmind.ticketworkflow.ticket.domain.exception;

/**
 * Raised when {@code Ticket.updateAssignment(...)} (SPEC-TW-030) would
 * leave team, support queue, and assignee all unchanged — mirrors {@link
 * ReassignmentRequiresDifferentAssigneeException}'s (SPEC-TW-008) spirit,
 * generalized to the broader set of fields this command can update.
 */
public class AssignmentRequiresAChangeException extends RuntimeException {

    public AssignmentRequiresAChangeException() {
        super("the requested team, support queue, and assignee are identical to the ticket's current ones");
    }
}
