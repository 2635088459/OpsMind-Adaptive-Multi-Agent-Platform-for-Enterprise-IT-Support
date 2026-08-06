package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-023 API contract §"Outcomes" (DLQ outcomes are consumer-level rejections raised before the use case is ever invoked). */
public enum ApplyVerificationSuccessOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    /** A conflicting terminal result (e.g. a failure) was already recorded for this attempt; the late success event never silently overwrites it. */
    CONFLICT_REQUIRES_RECONCILIATION
}
