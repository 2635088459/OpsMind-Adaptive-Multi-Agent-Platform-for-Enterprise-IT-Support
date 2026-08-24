package com.opsmind.policygovernance.domain.approval;

/**
 * Lifecycle status of an {@link ApprovalRequest} (03-state-machine §Approval
 * Request State Machine):
 *
 * <pre>
 * REQUESTED -> APPROVED
 * REQUESTED -> DENIED
 * REQUESTED -> EXPIRED
 * REQUESTED -> CANCELLED
 * REQUESTED -> SUPERSEDED
 * </pre>
 *
 * <p>Only {@code REQUESTED} can transition; every other status is final and
 * irreversible (INV-PG-007: denied/expired/cancelled must stay distinct —
 * they must never collapse into a single generic "denied").
 *
 * <p>{@code USED}/{@code REVOKED} are SPEC-PG-022's own addition —
 * 03-state-machine §Override State Machine draws a second, override-specific
 * continuation past {@code APPROVED}:
 *
 * <pre>
 * OVERRIDE_APPROVED -> OVERRIDE_USED
 * OVERRIDE_APPROVED -> OVERRIDE_REVOKED
 * </pre>
 *
 * reachable only from {@code APPROVED} and only when {@link
 * ApprovalRequest#approvalType()} is {@link ApprovalType#POLICY_OVERRIDE} —
 * see {@link ApprovalRequest#use} and {@link ApprovalRequest#revoke}. Both
 * are themselves final and irreversible, the same as every other non-{@code
 * REQUESTED} status here.
 */
public enum ApprovalStatus {
    REQUESTED,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
    SUPERSEDED,
    USED,
    REVOKED
}
