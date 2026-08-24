package com.opsmind.policygovernance.infrastructure.messaging.consumer;

/**
 * Thrown when a consumed event's body is not valid JSON, or its {@code
 * eventType} does not match what the listening queue expects, or a
 * required payload field is missing. Deliberately a plain {@code
 * RuntimeException}, not caught anywhere in this consumer: it propagates to
 * the {@code @RabbitListener} container, which (per {@code
 * RabbitConfig#toolApprovalEventsListenerContainerFactory}'s {@code
 * defaultRequeueRejected(false)}) rejects the message straight to its DLQ
 * rather than requeueing it forever — 10-failure-handling §Poison Decision
 * names exactly this scenario ("approval payload does not match source
 * linkage") as one to dead-letter, not retry indefinitely.
 */
public class ConsumedEventSchemaInvalidException extends RuntimeException {

    public ConsumedEventSchemaInvalidException(String message) {
        super(message);
    }

    public ConsumedEventSchemaInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
