package com.opsmind.identity.domain.stepup;

import com.opsmind.identity.domain.shared.DomainException;

/**
 * Thrown by {@link StepUpChallenge} for any transition 03-state-machine
 * §StepUpChallenge does not allow — including a second {@link
 * StepUpChallenge#consume} call on an already-{@code CONSUMED} challenge,
 * which is INV-UA-005 (replay resistance) made structural: {@code VERIFIED}
 * is the only legal source state for {@code consume}.
 */
public class IllegalStepUpTransitionException extends DomainException {

    private final StepUpStatus from;
    private final StepUpStatus to;

    public IllegalStepUpTransitionException(StepUpStatus from, StepUpStatus to) {
        super("cannot transition step-up challenge from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public StepUpStatus from() {
        return from;
    }

    public StepUpStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "STEPUP_ILLEGAL_TRANSITION";
    }
}
