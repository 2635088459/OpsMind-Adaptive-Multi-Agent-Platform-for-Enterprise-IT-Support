package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when an eligible actor lacks the {@code ticket:triage} scope, or
 * holds it but is not granted the target Support Queue's team (SPEC-TW-007
 * AC-07, domain-rules §6). Never reveals the actor's authorized queues.
 */
public class QueueAccessDeniedException extends RuntimeException {

    public QueueAccessDeniedException() {
        super("the actor is not authorized to triage into the target Support Queue");
    }
}
