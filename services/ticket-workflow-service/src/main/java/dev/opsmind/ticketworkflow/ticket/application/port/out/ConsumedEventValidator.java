package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Map;

/**
 * Validates an inbound integration event against the canonical envelope
 * schema and its event-specific payload schema (06-event-contracts §7/§22),
 * before any business or trust logic runs.
 */
public interface ConsumedEventValidator {

    void validateEnvelope(Map<String, Object> envelope);

    void validatePayload(String eventType, String eventVersion, Map<String, Object> payload);
}
