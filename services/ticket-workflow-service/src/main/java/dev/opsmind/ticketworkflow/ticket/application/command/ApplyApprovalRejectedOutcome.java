package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-016 API contract §"Outcomes": the four business-processing results (the two DLQ outcomes are consumer-level rejections raised before the use case is ever invoked). */
public enum ApplyApprovalRejectedOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    REJECTED_BUSINESS_RULE
}
