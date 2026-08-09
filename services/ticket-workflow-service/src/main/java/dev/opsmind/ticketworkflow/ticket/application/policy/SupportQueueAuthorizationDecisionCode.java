package dev.opsmind.ticketworkflow.ticket.application.policy;

/**
 * Decision and decision-code vocabulary for SPEC-TW-033 Support Queue
 * Authorization. Plain {@code String} constants (not an enum) to match this
 * codebase's existing convention for cross-cutting audit fields — {@code
 * AuditRecordEntry.decision()} and {@code .outcome()} are themselves plain
 * {@code String}s — and because {@code decisionCode} is also persisted
 * verbatim into a database column and an HTTP JSON response body.
 */
public final class SupportQueueAuthorizationDecisionCode {

    /** {@code decision} values — must match the {@code ticket.support_queue_authorization_decisions} CHECK constraint. */
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY = "DENY";
    public static final String DECISION_FAIL_CLOSED = "FAIL_CLOSED";

    /** {@code decisionCode} values. */
    public static final String ALLOWED = "POLICY_ALLOWED";
    public static final String DENIED_ACTOR_TYPE = "POLICY_DENIED_ACTOR_TYPE";
    public static final String DENIED_SCOPE = "POLICY_DENIED_SCOPE";
    public static final String OPERATION_NOT_SUPPORTED = "POLICY_OPERATION_NOT_SUPPORTED";
    public static final String CONTEXT_REQUIRED = "POLICY_CONTEXT_REQUIRED";
    public static final String FAIL_CLOSED_UNEXPECTED_ERROR = "POLICY_FAIL_CLOSED";

    private SupportQueueAuthorizationDecisionCode() {
    }
}
