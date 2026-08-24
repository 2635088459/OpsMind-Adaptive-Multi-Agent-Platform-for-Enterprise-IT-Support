package com.opsmind.policygovernance.application.exception;

/**
 * Thrown when a {@code POLICY_OVERRIDE} approval request is created without
 * the fields the override lifecycle requires. 03-state-machine §Override
 * State Machine: "Override must bind reason, scope, expiresAt, and
 * approver." UC-PG-006 (04-use-cases): "Override is valid only within
 * limited scope and time window." {@code reason} and {@code approver} are
 * already required generically at decision time
 * ({@code ApprovalDecision}'s own constructor); this guards the two fields
 * that are otherwise optional for every other approval type but are not
 * optional for an override: a non-null {@code expiresAt} (the time window)
 * and at least one {@code constraint} (the scope the override is limited
 * to).
 */
public class InvalidOverrideRequestException extends RuntimeException {

    public InvalidOverrideRequestException(String message) {
        super(message);
    }
}
