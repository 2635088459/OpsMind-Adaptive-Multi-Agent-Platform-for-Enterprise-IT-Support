package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.ReconciliationCaseRecord;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/** SPEC-TW-037 persistence: the reconciliation-case decision ledger. */
public interface ReconciliationCaseRepository {

    /** Summarizes every prior attempt for this exact {@code (ticketId, sourceReference)} pair. */
    ReconciliationCaseAttemptSummary summarize(TicketId ticketId, String sourceReference);

    /**
     * Appends a required recovery-attempt record. Implementations must not
     * swallow failures: a failed append must propagate so an {@code APPLIED}
     * decision that cannot be durably recorded never reaches the caller as a
     * success (mirrors {@link AuditRecordPort#append}).
     */
    void record(ReconciliationCaseRecord record);
}
