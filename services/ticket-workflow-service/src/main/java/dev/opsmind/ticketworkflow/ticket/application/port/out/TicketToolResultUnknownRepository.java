package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

public interface TicketToolResultUnknownRepository {

    /** SPEC-TW-021: unlike SPEC-TW-019/020's plain existence check, the caller needs to know which ticket and which outcome was already recorded to tell duplicate, conflict, and cross-ticket anomalies apart. */
    Optional<TicketToolExecutionExistingRecord> findExisting(String toolExecutionId);

    TicketToolResultUnknownUpdateOutcome recordUnknownResult(TicketToolResultUnknownUpdate update);

    /** Flags an already-recorded {@code COMPLETED}/{@code FAILED} row for reconciliation without touching its outcome — the late unknown-result event never silently overwrites it. Returns {@code false} if the row no longer belongs to {@code ticketId} (a cross-ticket anomaly the caller must not act on). */
    boolean markConflictRequiresReconciliation(TicketId ticketId, String toolExecutionId, String conflictEventId);
}
