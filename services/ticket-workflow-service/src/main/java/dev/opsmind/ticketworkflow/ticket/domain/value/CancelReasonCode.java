package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-029 API contract: the controlled cancel-reason vocabulary, covering both the requester and the authorized-support-actor paths. */
public enum CancelReasonCode {
    REQUESTER_CANCELLED,
    DUPLICATE_REQUEST,
    CREATED_IN_ERROR,
    NO_LONGER_NEEDED,
    SUPPORT_CANCELLED
}
