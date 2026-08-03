package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when the requested assignee is not an active member of the ticket's Support Queue (SPEC-TW-008 AC-05). */
public class AssigneeNotInQueueException extends RuntimeException {

    public AssigneeNotInQueueException() {
        super("the assignee is not a member of the ticket's support queue");
    }
}
