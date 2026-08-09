package dev.opsmind.ticketworkflow.ticket.application.policy;

/**
 * Decision and decision-code vocabulary for SPEC-TW-035 Secret Detection.
 * Plain {@code String} constants, mirroring {@link
 * SupportQueueAuthorizationDecisionCode}/{@link
 * SensitiveReadAuditDecisionCode}'s rationale. The {@code DENIED_*}
 * constants mirror {@code SecretPatternCategory} 1:1 so a denial's
 * decisionCode stays a fixed, low-cardinality label without ever
 * embedding the matched text.
 */
public final class SecretDetectionDecisionCode {

    /** {@code decision} values — must match the {@code ticket.secret_detection_decisions} CHECK constraint. */
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY = "DENY";
    public static final String DECISION_FAIL_CLOSED = "FAIL_CLOSED";

    /** {@code decisionCode} values. */
    public static final String ALLOWED = "POLICY_ALLOWED";
    public static final String OPERATION_NOT_SUPPORTED = "POLICY_OPERATION_NOT_SUPPORTED";
    public static final String FAIL_CLOSED_DECISION_AUDIT = "POLICY_FAIL_CLOSED";

    public static final String DENIED_PRIVATE_KEY_BLOCK = "POLICY_DENIED_SECRET_PRIVATE_KEY_BLOCK";
    public static final String DENIED_PASSWORD_ASSIGNMENT = "POLICY_DENIED_SECRET_PASSWORD_ASSIGNMENT";
    public static final String DENIED_API_KEY_ASSIGNMENT = "POLICY_DENIED_SECRET_API_KEY_ASSIGNMENT";
    public static final String DENIED_AUTHORIZATION_HEADER = "POLICY_DENIED_SECRET_AUTHORIZATION_HEADER";
    public static final String DENIED_BEARER_TOKEN = "POLICY_DENIED_SECRET_BEARER_TOKEN";
    public static final String DENIED_AWS_ACCESS_KEY = "POLICY_DENIED_SECRET_AWS_ACCESS_KEY";

    private SecretDetectionDecisionCode() {
    }
}
