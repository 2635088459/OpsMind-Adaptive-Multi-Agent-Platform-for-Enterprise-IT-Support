package com.opsmind.identity.application.port.out;

/** {@link PasswordGrantPort#exchange}'s own result — the real tokens Keycloak's token endpoint issued for the grant. {@code idToken} is required for {@code PasswordLoginController} to build a verified {@code OidcUser} the same way the redirect flow's own {@code oauth2Login} does. */
public record PasswordGrantResult(String accessToken, String refreshToken, String idToken, long expiresInSeconds) {
}
