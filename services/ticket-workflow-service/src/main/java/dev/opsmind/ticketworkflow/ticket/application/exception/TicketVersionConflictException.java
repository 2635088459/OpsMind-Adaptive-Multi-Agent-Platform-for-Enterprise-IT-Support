package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when the client's {@code If-Match} version does not match the
 * Ticket's current version (SPEC-TW-007 AC-10). Carries {@link
 * #currentVersion()} so the caller can render the current {@code ETag}
 * without a second read.
 */
public class TicketVersionConflictException extends RuntimeException {

    private final long currentVersion;

    public TicketVersionConflictException(long currentVersion) {
        super("the ticket was changed by another operation");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
