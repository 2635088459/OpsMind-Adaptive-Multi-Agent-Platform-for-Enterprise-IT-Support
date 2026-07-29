package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when {@code subcategoryId} does not exist, is inactive, or does
 * not belong to the selected {@code categoryId} (SPEC-TW-007 AC-04).
 */
public class TriageSubcategoryInvalidException extends RuntimeException {

    public TriageSubcategoryInvalidException() {
        super("the subcategory does not exist, is not active, or does not belong to the selected category");
    }
}
