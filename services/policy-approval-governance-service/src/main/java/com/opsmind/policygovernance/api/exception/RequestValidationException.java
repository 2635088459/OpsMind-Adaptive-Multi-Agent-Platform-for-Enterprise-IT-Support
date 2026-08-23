package com.opsmind.policygovernance.api.exception;

/** Thrown by controllers for request-shape problems Jakarta Validation does not cover (e.g. a missing correlation id header). */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
