package com.opsmind.policygovernance.domain.shared;

/**
 * Base type for governance domain-rule violations. Every subclass carries a
 * stable, machine-readable {@link #code()} so the API layer (see {@code
 * GlobalRestExceptionHandler}) can map it to a governance-appropriate HTTP
 * status without leaking internal exception class names or messages.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    public abstract String code();
}
