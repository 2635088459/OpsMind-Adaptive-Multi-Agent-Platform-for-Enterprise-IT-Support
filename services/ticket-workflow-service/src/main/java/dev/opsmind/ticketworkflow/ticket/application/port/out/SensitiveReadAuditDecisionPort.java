package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditDecisionEntry;

public interface SensitiveReadAuditDecisionPort {

    /**
     * Appends a required Sensitive Read Audit policy-decision record
     * (SPEC-TW-034 persistence). Implementations must not swallow failures:
     * a failed append must propagate so an {@code ALLOW} decision that
     * cannot be durably recorded fails closed rather than being silently
     * granted (domain-rules: "Sensitive details must not be returned when
     * required audit persistence fails").
     */
    void record(SensitiveReadAuditDecisionEntry entry);
}
