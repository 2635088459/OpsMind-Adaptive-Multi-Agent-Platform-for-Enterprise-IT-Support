package dev.opsmind.ticketworkflow.ticket.application.exception;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("idempotency key reused with a different payload: " + idempotencyKey);
    }
}
