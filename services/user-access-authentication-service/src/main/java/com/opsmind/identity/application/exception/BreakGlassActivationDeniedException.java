package com.opsmind.identity.application.exception;

/**
 * SPEC-UA-019 (11-security: "Break-glass requires strong authentication,
 * domain-06 approval/dual control, bounded scope/time, and non-disableable
 * audit"). Thrown whenever any of those preconditions is not met: no
 * approval reference, no reason, no session at the required assurance
 * level, or a requested duration exceeding the bounded maximum.
 */
public class BreakGlassActivationDeniedException extends RuntimeException {

    public BreakGlassActivationDeniedException(String reason) {
        super("break-glass activation denied: " + reason);
    }
}
