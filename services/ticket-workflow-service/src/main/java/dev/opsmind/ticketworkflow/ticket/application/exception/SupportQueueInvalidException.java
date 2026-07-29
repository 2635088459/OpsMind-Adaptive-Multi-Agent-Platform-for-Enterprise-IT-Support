package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Raised when {@code supportQueueId} does not exist or is inactive (SPEC-TW-007 AC-06). */
public class SupportQueueInvalidException extends RuntimeException {

    public SupportQueueInvalidException() {
        super("the support queue does not exist or is not active");
    }
}
