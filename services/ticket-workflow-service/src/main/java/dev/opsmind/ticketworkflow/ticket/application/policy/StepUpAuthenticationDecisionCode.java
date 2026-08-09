package dev.opsmind.ticketworkflow.ticket.application.policy;

/**
 * Decision and decision-code vocabulary for SPEC-TW-036 Step-up
 * Authentication. Plain {@code String} constants, mirroring {@link
 * SupportQueueAuthorizationDecisionCode}/{@link SensitiveReadAuditDecisionCode}/{@link
 * SecretDetectionDecisionCode}'s rationale.
 */
public final class StepUpAuthenticationDecisionCode {

    /** {@code decision} values — must match the {@code ticket.step_up_authentication_decisions} CHECK constraint. */
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY = "DENY";
    public static final String DECISION_FAIL_CLOSED = "FAIL_CLOSED";

    /** {@code decisionCode} values. */
    public static final String ALLOWED = "POLICY_ALLOWED";
    public static final String OPERATION_NOT_SUPPORTED = "POLICY_OPERATION_NOT_SUPPORTED";
    public static final String DENIED_STEP_UP_MISSING = "POLICY_DENIED_STEP_UP_MISSING";
    public static final String DENIED_STEP_UP_INVALID = "POLICY_DENIED_STEP_UP_INVALID";
    public static final String DENIED_STEP_UP_EXPIRED = "POLICY_DENIED_STEP_UP_EXPIRED";
    public static final String FAIL_CLOSED_DECISION_AUDIT = "POLICY_FAIL_CLOSED";

    private StepUpAuthenticationDecisionCode() {
    }
}
