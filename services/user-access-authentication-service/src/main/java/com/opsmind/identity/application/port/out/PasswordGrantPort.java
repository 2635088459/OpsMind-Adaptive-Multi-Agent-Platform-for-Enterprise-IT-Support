package com.opsmind.identity.application.port.out;

import com.opsmind.identity.application.exception.InvalidPasswordGrantException;

/**
 * 13-package-and-class-design §Output Ports. {@code PasswordLoginController}'s
 * own real Resource Owner Password Credentials grant against the IdP — the
 * inline-form login both SPAs submit instead of a top-level navigation to
 * Keycloak's own hosted login page. Same "real IdP call behind a port"
 * pattern {@link OidcProviderPort} already established, kept separate from
 * it since exchanging credentials for a token is a distinct concern from
 * discovery/end-session.
 */
public interface PasswordGrantPort {

    /**
     * @throws InvalidPasswordGrantException the IdP rejected the grant (bad
     *     credentials, a locked/disabled user, or a client this grant is not
     *     enabled for) — never distinguishes which, to avoid username
     *     enumeration.
     */
    PasswordGrantResult exchange(String tokenEndpoint, String clientId, String clientSecret, String username, String password);
}
