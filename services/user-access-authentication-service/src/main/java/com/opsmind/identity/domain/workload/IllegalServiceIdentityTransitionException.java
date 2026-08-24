package com.opsmind.identity.domain.workload;

import com.opsmind.identity.domain.shared.DomainException;

/** Thrown by {@link ServiceIdentity} for any transition 03-state-machine §ServiceIdentity does not allow. {@code RETIRED} is terminal. */
public class IllegalServiceIdentityTransitionException extends DomainException {

    private final ServiceIdentityStatus from;
    private final ServiceIdentityStatus to;

    public IllegalServiceIdentityTransitionException(ServiceIdentityStatus from, ServiceIdentityStatus to) {
        super("cannot transition service identity from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public ServiceIdentityStatus from() {
        return from;
    }

    public ServiceIdentityStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "SERVICE_IDENTITY_ILLEGAL_TRANSITION";
    }
}
