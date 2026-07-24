package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Map;

public interface EventSchemaValidator {

    /**
     * Validates an event payload against its published JSON Schema.
     * Throws {@link dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException}
     * when the payload does not conform.
     */
    void validate(String eventType, String eventVersion, Map<String, Object> payload);
}
