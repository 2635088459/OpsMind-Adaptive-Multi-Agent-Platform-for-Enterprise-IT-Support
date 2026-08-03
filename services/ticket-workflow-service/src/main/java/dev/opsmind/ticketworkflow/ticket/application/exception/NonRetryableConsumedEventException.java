package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-015 event-contracts §14 "Immediate DLQ": marker base for consumer
 * failures that must never be retried (schema-invalid or untrusted-producer
 * messages will fail identically on every redelivery). The listener
 * container's retry policy only retries transient exceptions and treats
 * every subclass of this type as an immediate reject-to-DLQ.
 */
public abstract class NonRetryableConsumedEventException extends RuntimeException {

    protected NonRetryableConsumedEventException(String message) {
        super(message);
    }
}
