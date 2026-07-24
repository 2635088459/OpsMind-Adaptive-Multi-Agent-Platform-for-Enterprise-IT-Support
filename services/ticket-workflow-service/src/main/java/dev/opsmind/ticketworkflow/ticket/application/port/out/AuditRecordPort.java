package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;

public interface AuditRecordPort {

    /**
     * Appends a required audit record. Implementations must not swallow
     * failures: a failed append must propagate so the caller's transaction
     * rolls back (Create Ticket fails closed on audit failure).
     */
    void append(AuditRecordEntry entry);
}
