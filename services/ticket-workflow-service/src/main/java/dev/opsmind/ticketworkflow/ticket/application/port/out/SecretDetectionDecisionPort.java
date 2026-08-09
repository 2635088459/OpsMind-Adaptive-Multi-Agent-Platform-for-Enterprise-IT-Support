package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.SecretDetectionDecisionEntry;

public interface SecretDetectionDecisionPort {

    /**
     * Appends a required Secret Detection policy-decision record
     * (SPEC-TW-035 persistence). Implementations must not swallow failures:
     * a failed append must propagate so an {@code ALLOW} decision that
     * cannot be durably recorded fails closed rather than being silently
     * granted.
     */
    void record(SecretDetectionDecisionEntry entry);
}
