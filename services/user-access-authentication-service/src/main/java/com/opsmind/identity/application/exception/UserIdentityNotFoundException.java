package com.opsmind.identity.application.exception;

public class UserIdentityNotFoundException extends RuntimeException {

    public UserIdentityNotFoundException(String userIdentityId) {
        super("user identity " + userIdentityId + " was not found");
    }
}
