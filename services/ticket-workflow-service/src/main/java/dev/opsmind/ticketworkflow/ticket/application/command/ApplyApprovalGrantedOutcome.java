package dev.opsmind.ticketworkflow.ticket.application.command;

/** SPEC-TW-015 API contract §"Internal outcomes": the four business-processing results (the two DLQ outcomes are consumer-level rejections raised before the use case is ever invoked). */
public enum ApplyApprovalGrantedOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    REJECTED_BUSINESS_RULE
}
