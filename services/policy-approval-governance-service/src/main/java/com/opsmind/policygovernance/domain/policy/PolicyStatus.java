package com.opsmind.policygovernance.domain.policy;

/**
 * Lifecycle status of a {@link PolicyVersion} (03-state-machine §Policy
 * State Machine):
 *
 * <pre>
 * DRAFT -> REVIEWING -> PUBLISHED -> DEPRECATED -> ARCHIVED
 * DRAFT -> CANCELLED
 * REVIEWING -> REJECTED -> DRAFT
 * PUBLISHED -> SUPERSEDED
 * </pre>
 */
public enum PolicyStatus {
    DRAFT,
    REVIEWING,
    REJECTED,
    PUBLISHED,
    SUPERSEDED,
    DEPRECATED,
    ARCHIVED,
    CANCELLED
}
