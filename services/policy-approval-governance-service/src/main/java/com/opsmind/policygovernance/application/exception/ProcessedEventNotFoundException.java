package com.opsmind.policygovernance.application.exception;

/** SPEC-PG-034: thrown when a backfill is requested for an {@code (eventId, consumerName)} pair that was never marked processed. */
public class ProcessedEventNotFoundException extends RuntimeException {

    public ProcessedEventNotFoundException(String eventId, String consumerName) {
        super("processed event " + eventId + " for consumer " + consumerName + " was not found");
    }
}
