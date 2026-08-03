package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-011 API contract §3: the controlled close-reason vocabulary. */
public enum CloseReasonCode {
    REQUESTER_CONFIRMED,
    SUPPORT_CONFIRMED,
    AUTO_CLOSE_TIMEOUT,
    NO_FURTHER_ACTION_REQUIRED,
    DUPLICATE_CLOSED
}
