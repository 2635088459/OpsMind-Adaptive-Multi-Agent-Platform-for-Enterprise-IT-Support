package com.opsmind.identity.application.exception;

/** INV-UA-002 (deny by default): a session or step-up challenge cannot be started/requested for a non-{@code ACTIVE} user identity. */
public class UserIdentityNotEligibleException extends RuntimeException {

    public UserIdentityNotEligibleException(String userIdentityId) {
        super("user identity " + userIdentityId + " is not eligible: not ACTIVE");
    }
}
