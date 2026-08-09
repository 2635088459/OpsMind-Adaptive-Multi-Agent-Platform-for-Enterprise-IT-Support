package dev.opsmind.ticketworkflow.ticket.application.policy;

/**
 * Decision and decision-code vocabulary for SPEC-TW-034 Sensitive Read
 * Audit. Plain {@code String} constants, mirroring {@link
 * SupportQueueAuthorizationDecisionCode}'s rationale: {@code
 * AuditRecordEntry.decision()} is itself a plain {@code String}, and {@code
 * decisionCode} is persisted verbatim into a database column and an HTTP
 * JSON response body.
 */
public final class SensitiveReadAuditDecisionCode {

    /** {@code decision} values — must match the {@code ticket.sensitive_read_audit_decisions} CHECK constraint. */
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY = "DENY";
    public static final String DECISION_FAIL_CLOSED = "FAIL_CLOSED";

    /** {@code decisionCode} values. */
    public static final String ALLOWED = "POLICY_ALLOWED";
    public static final String DENIED_ACTOR_TYPE = "POLICY_DENIED_ACTOR_TYPE";
    public static final String OPERATION_NOT_SUPPORTED = "POLICY_OPERATION_NOT_SUPPORTED";
    public static final String FAIL_CLOSED_AUDIT_PERSISTENCE = "POLICY_FAIL_CLOSED";

    private SensitiveReadAuditDecisionCode() {
    }
}
