package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;

public interface SensitiveReadAuditPort {

    /**
     * Appends a required sensitive-read audit record. Implementations must
     * not swallow failures: the Get Ticket read fails closed when this
     * throws (SPEC-TW-002 §16).
     */
    void recordSensitiveRead(SensitiveReadAuditEntry entry);
}
