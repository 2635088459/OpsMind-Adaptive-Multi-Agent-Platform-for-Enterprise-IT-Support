package com.opsmind.identity.application.exception;

public class ServiceIdentityNotFoundException extends RuntimeException {

    public ServiceIdentityNotFoundException(String serviceIdentityId) {
        super("service identity " + serviceIdentityId + " was not found");
    }
}
