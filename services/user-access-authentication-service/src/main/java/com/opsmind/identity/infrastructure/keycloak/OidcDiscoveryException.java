package com.opsmind.identity.infrastructure.keycloak;

/** SPEC-UA-004: discovery is unreachable or malformed — callers must fail closed (INV-UA-002), never fall back to a guessed/partial configuration. */
public class OidcDiscoveryException extends RuntimeException {

    public OidcDiscoveryException(String message) {
        super(message);
    }

    public OidcDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
