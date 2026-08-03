package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when the requested assignee exists but is not active (SPEC-TW-008 AC-05). */
public class AssigneeInactiveException extends RuntimeException {

    public AssigneeInactiveException() {
        super("the assignee is not active");
    }
}
