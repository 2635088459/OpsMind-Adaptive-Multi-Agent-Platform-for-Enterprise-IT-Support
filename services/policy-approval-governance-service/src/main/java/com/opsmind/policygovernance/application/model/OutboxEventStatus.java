package com.opsmind.policygovernance.application.model;

/** Dispatch status of an {@link OutboxEventRecord} row (07-data-model §outbox_events). */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
