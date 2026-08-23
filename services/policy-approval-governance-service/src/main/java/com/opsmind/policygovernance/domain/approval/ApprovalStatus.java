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
 */
public enum ApprovalStatus {
    REQUESTED,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
    SUPERSEDED
}
