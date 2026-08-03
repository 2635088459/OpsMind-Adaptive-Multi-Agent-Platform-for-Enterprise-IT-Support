package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when the requested assignee's directory role is not support-capable (SPEC-TW-008 AC-05). */
public class AssigneeNotSupportAgentException extends RuntimeException {

    public AssigneeNotSupportAgentException() {
        super("the assignee does not hold a support-capable role");
    }
}
