package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Signals that {@code TicketRepository.save} could not insert a ticket because
 * the generated display id collided with an existing one. Callers regenerate
 * the display id and retry a bounded number of times.
 */
public class DisplayIdCollisionException extends RuntimeException {

    public DisplayIdCollisionException(String displayId, Throwable cause) {
        super("display id already in use: " + displayId, cause);
    }
}
