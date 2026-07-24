package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Thrown when an outgoing integration event payload fails schema validation.
 * Callers must let this roll back the enclosing business transaction
 * (EVENT_SCHEMA_GENERATION_FAILED).
 */
public class EventSchemaValidationException extends RuntimeException {

    public EventSchemaValidationException(String eventType, String eventVersion, String reason) {
        super("event " + eventType + " v" + eventVersion + " failed schema validation: " + reason);
    }
}
