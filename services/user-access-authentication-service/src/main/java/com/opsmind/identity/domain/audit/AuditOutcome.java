package com.opsmind.identity.domain.audit;

/** The outcome of the action an {@link IdentityAuditRecord} describes. */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
