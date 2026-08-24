package com.opsmind.identity.domain.shared;

/**
 * Base type for identity domain-rule violations. Every subclass carries a
 * stable, machine-readable {@link #code()} so the API layer (see {@code
 * platform.error.GlobalRestExceptionHandler}) can map it to an
 * identity-appropriate HTTP status without leaking internal exception class
 * names, messages, or token-validation internals (api-contract: "security
 * errors do not reveal token-validation internals").
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    public abstract String code();
}
