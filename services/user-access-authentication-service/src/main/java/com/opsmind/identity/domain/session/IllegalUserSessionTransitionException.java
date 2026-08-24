package com.opsmind.identity.domain.session;

import com.opsmind.identity.domain.shared.DomainException;

/** Thrown by {@link UserSession} for any transition 03-state-machine §UserSession does not allow — every non-{@code ACTIVE} state is final. */
public class IllegalUserSessionTransitionException extends DomainException {

    private final SessionStatus from;
    private final SessionStatus to;

    public IllegalUserSessionTransitionException(SessionStatus from, SessionStatus to) {
        super("cannot transition user session from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public SessionStatus from() {
        return from;
    }

    public SessionStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "USER_SESSION_ILLEGAL_TRANSITION";
    }
}
