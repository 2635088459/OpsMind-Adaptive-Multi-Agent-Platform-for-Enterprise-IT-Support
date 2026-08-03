package dev.opsmind.ticketworkflow.ticket.application.exception;

/** Thrown when an inbound integration event's envelope or payload fails JSON Schema validation (DLQ_SCHEMA_INVALID). */
public class ConsumedEventSchemaInvalidException extends NonRetryableConsumedEventException {

    public ConsumedEventSchemaInvalidException(String eventType, String reason) {
        super("consumed event " + eventType + " failed schema validation: " + reason);
    }
}
