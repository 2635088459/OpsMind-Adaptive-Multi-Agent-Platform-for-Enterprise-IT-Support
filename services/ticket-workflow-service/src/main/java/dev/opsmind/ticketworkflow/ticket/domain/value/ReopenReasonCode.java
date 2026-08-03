package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-011 API contract §4: the controlled reopen-reason vocabulary. */
public enum ReopenReasonCode {
    ISSUE_RECURRED,
    RESOLUTION_FAILED,
    REQUESTER_REPORTED_NOT_FIXED,
    SUPPORT_REVIEW_REQUIRED,
    RELATED_FAILURE_DISCOVERED
}
