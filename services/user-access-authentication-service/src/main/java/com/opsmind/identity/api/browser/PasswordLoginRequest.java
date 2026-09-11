package com.opsmind.identity.api.browser;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code PasswordLoginController}'s own request body — the inline-form login
 * both SPAs submit instead of navigating to Keycloak's own hosted page.
 * {@code registrationId} picks which of the two real Keycloak client
 * registrations to authenticate against (each carries its own scope set,
 * least privilege — see {@code SecurityConfig}'s own
 * {@code spring.security.oauth2.client.registration.*} javadoc for why);
 * it is validated against an explicit allow-list in the controller, never
 * trusted to name an arbitrary registration.
 */
public record PasswordLoginRequest(
    @NotBlank String registrationId,
    @NotBlank String username,
    @NotBlank String password
) {
}
