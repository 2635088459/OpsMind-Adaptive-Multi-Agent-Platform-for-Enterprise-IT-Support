package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-020 API contract §"Outcomes" (the two DLQ outcomes are consumer-level rejections raised before the use case is ever invoked). */
public enum ApplyToolExecutionFailedOutcome {
    APPLIED_SAFE_FAILURE,
    APPLIED_PIPELINE_FAILURE,
    DUPLICATE,
    STALE
}
