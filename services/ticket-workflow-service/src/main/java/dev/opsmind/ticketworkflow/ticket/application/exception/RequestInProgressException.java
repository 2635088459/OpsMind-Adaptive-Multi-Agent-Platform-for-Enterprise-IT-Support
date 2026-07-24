package dev.opsmind.ticketworkflow.ticket.application.exception;

public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String idempotencyKey) {
        super("an identical request is already in progress: " + idempotencyKey);
    }
}
