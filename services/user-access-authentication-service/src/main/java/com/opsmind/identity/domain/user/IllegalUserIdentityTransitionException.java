package com.opsmind.identity.domain.user;

import com.opsmind.identity.domain.shared.DomainException;

/** Thrown by {@link UserIdentity} for any transition 03-state-machine §UserIdentity does not allow. {@code DEPROVISIONED} is terminal (irreversible). */
public class IllegalUserIdentityTransitionException extends DomainException {

    private final UserStatus from;
    private final UserStatus to;

    public IllegalUserIdentityTransitionException(UserStatus from, UserStatus to) {
        super("cannot transition user identity from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public UserStatus from() {
        return from;
    }

    public UserStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "USER_IDENTITY_ILLEGAL_TRANSITION";
    }
}
