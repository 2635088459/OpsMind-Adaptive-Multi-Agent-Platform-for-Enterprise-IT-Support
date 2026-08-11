package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.CorrectionEventRecord;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

/** SPEC-TW-039 persistence: the correction-event decision ledger. */
public interface CorrectionEventRepository {

    /** Summarizes every prior attempt for this exact {@code (ticketId, sourceReference)} pair. */
    CorrectionEventAttemptSummary summarize(TicketId ticketId, String sourceReference);

    /**
     * Appends a required recovery-attempt record. Implementations must not
     * swallow failures: a failed append must propagate so an {@code APPLIED}
     * decision that cannot be durably recorded never reaches the caller as a
     * success (mirrors {@link ReconciliationCaseRepository#record}).
     */
    void record(CorrectionEventRecord record);
}
