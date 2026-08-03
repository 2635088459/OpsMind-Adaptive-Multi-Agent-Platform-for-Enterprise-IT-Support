package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when the requested assignee does not exist in the support agent directory (SPEC-TW-008 AC-05). */
public class AssigneeNotFoundException extends RuntimeException {

    public AssigneeNotFoundException() {
        super("the assignee does not exist");
    }
}
