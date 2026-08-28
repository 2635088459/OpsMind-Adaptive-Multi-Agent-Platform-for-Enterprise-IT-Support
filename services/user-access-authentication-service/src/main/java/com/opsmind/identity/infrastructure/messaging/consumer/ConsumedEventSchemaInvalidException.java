package com.opsmind.identity.infrastructure.messaging.consumer;

/**
 * SPEC-UA-028 (10-failure-handling §Poison Decision). Thrown by a consumer
 * when a message cannot be parsed into the expected envelope/payload
 * shape, or carries an unexpected {@code eventType} — the listener
 * container's own {@code defaultRequeueRejected(false)} then sends it
 * straight to the dead-letter queue rather than retrying forever.
 */
public class ConsumedEventSchemaInvalidException extends RuntimeException {

    public ConsumedEventSchemaInvalidException(String message) {
        super(message);
    }

    public ConsumedEventSchemaInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
