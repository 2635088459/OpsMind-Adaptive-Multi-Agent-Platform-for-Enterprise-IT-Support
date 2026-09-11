package com.opsmind.identity.application.exception;

/** {@code PasswordGrantPort#exchange}: the IdP rejected the grant. Never says which of username/password was wrong (no enumeration). */
public class InvalidPasswordGrantException extends RuntimeException {

    public InvalidPasswordGrantException() {
        super("invalid username, password, or grant");
    }
}
