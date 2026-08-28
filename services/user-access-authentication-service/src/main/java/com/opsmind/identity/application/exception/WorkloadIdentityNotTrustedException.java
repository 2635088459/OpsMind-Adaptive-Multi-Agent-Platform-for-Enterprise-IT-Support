package com.opsmind.identity.application.exception;

/**
 * INV-UA-002 (deny by default): thrown whenever a bearer JWT cannot be
 * trusted as belonging to a currently-registered, {@code ACTIVE}, in-window
 * {@code ServiceIdentity} with a matching audience/scope — no registered
 * identity at all, a disabled/retired/not-yet-valid/expired one, or a token
 * presenting an audience or scope outside that identity's own allow-list.
 */
public class WorkloadIdentityNotTrustedException extends RuntimeException {

    public WorkloadIdentityNotTrustedException(String subject, String reason) {
        super("workload identity for subject " + subject + " is not trusted: " + reason);
    }
}
