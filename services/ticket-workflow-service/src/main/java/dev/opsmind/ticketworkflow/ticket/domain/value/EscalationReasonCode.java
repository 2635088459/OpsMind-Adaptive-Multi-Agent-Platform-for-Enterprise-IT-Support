package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-031 API contract: controlled reason vocabulary for moving a Ticket into human/senior-support governance. */
public enum EscalationReasonCode {
    USER_IMPACT,
    SLA_RISK,
    SECURITY_RISK,
    REPEATED_FAILURE,
    POLICY_REQUIRED,
    SUPPORT_REQUEST
}
