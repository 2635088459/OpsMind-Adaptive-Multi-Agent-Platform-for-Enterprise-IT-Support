package com.opsmind.identity.application.exception;

public class UserSessionNotFoundException extends RuntimeException {

    public UserSessionNotFoundException(String userSessionId) {
        super("user session " + userSessionId + " was not found");
    }
}
