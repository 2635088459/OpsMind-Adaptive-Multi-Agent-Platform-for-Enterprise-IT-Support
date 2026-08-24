package com.opsmind.identity.api.error;

/** Thrown by a controller for a request-shape problem bean validation does not already catch (e.g. a missing verified-JWT principal). */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
