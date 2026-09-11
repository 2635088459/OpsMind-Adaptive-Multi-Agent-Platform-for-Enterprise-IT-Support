package com.opsmind.identity.infrastructure.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.exception.InvalidPasswordGrantException;
import com.opsmind.identity.application.port.out.PasswordGrantPort;
import com.opsmind.identity.application.port.out.PasswordGrantResult;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link PasswordGrantPort}'s real Keycloak implementation — the Resource
 * Owner Password Credentials grant behind {@code PasswordLoginController}.
 * Mirrors {@link KeycloakAdminClient}'s own {@code RestClient}/form-encoded
 * pattern exactly, one grant type over.
 *
 * <p>Authenticates with the SAME confidential client id/secret {@code
 * spring.security.oauth2.client.registration.*} already holds for the
 * Authorization Code flow — the secret never leaves this server process;
 * the browser only ever sends this service a username/password over the
 * session it already trusts (same-origin as every other BFF call), never
 * Keycloak's own token endpoint directly.
 */
@Component
public class KeycloakPasswordGrantClient implements PasswordGrantPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KeycloakPasswordGrantClient() {
        this(RestClient.create());
    }

    KeycloakPasswordGrantClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PasswordGrantResult exchange(String tokenEndpoint, String clientId, String clientSecret, String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret == null ? "" : clientSecret);
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid profile email");

        String body;
        try {
            body = restClient.post().uri(tokenEndpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        } catch (HttpClientErrorException e) {
            // Keycloak's token endpoint returns 400/401 with {"error":"invalid_grant",...} for
            // bad credentials, a locked/disabled user, or a client this grant isn't enabled for
            // — every one of those is "sign-in failed," not a system fault.
            throw new InvalidPasswordGrantException();
        } catch (Exception e) {
            throw new OidcDiscoveryException("failed to call Keycloak's token endpoint for a password grant", e);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.has("access_token")) {
                throw new OidcDiscoveryException("Keycloak password-grant token response is missing access_token");
            }
            return new PasswordGrantResult(
                root.get("access_token").asText(),
                root.hasNonNull("refresh_token") ? root.get("refresh_token").asText() : null,
                root.hasNonNull("id_token") ? root.get("id_token").asText() : null,
                root.path("expires_in").asLong(0)
            );
        } catch (OidcDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcDiscoveryException("malformed Keycloak password-grant token response", e);
        }
    }
}
