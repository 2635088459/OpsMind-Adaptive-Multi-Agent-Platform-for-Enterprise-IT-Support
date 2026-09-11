package com.opsmind.identity.api.error;

/** {@code PasswordLoginController}: {@code registrationId} named something other than the two real, allow-listed browser-login client registrations. */
public class PasswordLoginRegistrationNotAllowedException extends RuntimeException {

    public PasswordLoginRegistrationNotAllowedException(String registrationId) {
        super("registration '" + registrationId + "' does not accept a direct-grant login");
    }
}
