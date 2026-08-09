package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-032 API contract: controlled reason vocabulary for resuming a Ticket out of ESCALATED governance back into active work. */
public enum EscalationResumeReasonCode {
    ROOT_CAUSE_RESOLVED,
    MITIGATION_APPLIED,
    RISK_ACCEPTED,
    ESCALATION_NOT_REQUIRED,
    OWNER_REASSIGNED
}
