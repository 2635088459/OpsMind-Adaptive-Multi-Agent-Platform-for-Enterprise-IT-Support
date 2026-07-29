package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when {@code categoryId} does not exist or is inactive (SPEC-TW-007 AC-03). */
public class TriageCategoryInvalidException extends RuntimeException {

    public TriageCategoryInvalidException() {
        super("the category does not exist or is not active");
    }
}
