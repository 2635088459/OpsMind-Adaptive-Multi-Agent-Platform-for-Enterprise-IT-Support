package com.opsmind.identity.application.exception;

public class BreakGlassGrantNotFoundException extends RuntimeException {

    public BreakGlassGrantNotFoundException(String breakGlassGrantId) {
        super("break-glass grant " + breakGlassGrantId + " was not found");
    }
}
