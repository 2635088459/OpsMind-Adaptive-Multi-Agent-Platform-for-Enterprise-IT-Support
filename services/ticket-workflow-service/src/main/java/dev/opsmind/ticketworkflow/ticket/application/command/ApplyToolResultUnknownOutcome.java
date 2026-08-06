package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-021 API contract §"Outcomes" (the two DLQ outcomes are consumer-level rejections raised before the use case is ever invoked). */
public enum ApplyToolResultUnknownOutcome {
    RECORDED_UNKNOWN,
    DUPLICATE,
    STALE,
    /** A completed/failed outcome was already recorded for this {@code toolExecutionId}; the late unknown-result event never silently overwrites it — it is flagged for reconciliation instead. */
    CONFLICT_REQUIRES_RECONCILIATION
}
