package dev.opsmind.ticketworkflow.ticket.application.exception;

public class TicketAuthorizationException extends RuntimeException {

    public TicketAuthorizationException(String requiredScope) {
        super("actor is missing required scope: " + requiredScope);
    }
}
