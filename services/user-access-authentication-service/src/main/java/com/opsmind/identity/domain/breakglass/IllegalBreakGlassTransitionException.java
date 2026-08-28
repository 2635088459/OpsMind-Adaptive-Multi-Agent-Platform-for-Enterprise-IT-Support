package com.opsmind.identity.domain.breakglass;

import com.opsmind.identity.domain.shared.DomainException;

/** Thrown by {@link BreakGlassGrant} for any transition its own state machine does not allow. */
public class IllegalBreakGlassTransitionException extends DomainException {

    private final BreakGlassStatus from;
    private final BreakGlassStatus to;

    public IllegalBreakGlassTransitionException(BreakGlassStatus from, BreakGlassStatus to) {
        super("cannot transition break-glass grant from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public BreakGlassStatus from() {
        return from;
    }

    public BreakGlassStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "BREAK_GLASS_ILLEGAL_TRANSITION";
    }
}
