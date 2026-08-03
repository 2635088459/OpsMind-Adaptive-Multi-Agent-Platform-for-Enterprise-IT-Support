package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-012 persistence: {@code ticket.ticket_user_input_requests.request_status}'s controlled vocabulary. */
public enum UserInputRequestStatus {
    OPEN,
    ANSWERED,
    CANCELLED,
    EXPIRED
}
