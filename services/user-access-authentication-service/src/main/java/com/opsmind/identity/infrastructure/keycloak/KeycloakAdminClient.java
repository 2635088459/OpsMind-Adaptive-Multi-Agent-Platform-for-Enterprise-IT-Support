package com.opsmind.identity.infrastructure.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * SPEC-UA-009: the real Keycloak Admin REST call behind {@code
 * KeycloakOidcProviderAdapter#requestEndSession} — {@code POST
 * /admin/realms/{realm}/users/{userId}/logout}, which invalidates every
 * session Keycloak holds for that user without needing a raw ID token or
 * session reference (INV-UA-001 forbids storing either; the already-stored,
 * never-hashed {@code sub} claim is all this needs). Authenticates via its
 * own client-credentials grant against the realm's normal token endpoint —
 * a separate client registration from the browser-login one, only used
 * when {@code KeycloakAdminProperties#isConfigured()}.
 */
@Component
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KeycloakAdminClient() {
        this(RestClient.create());
    }

    KeycloakAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws OidcDiscoveryException wraps any token-acquisition or logout-call failure — the caller
     *     (SPEC-UA-009's own retry/reconciliation) treats this as "not yet notified."
     */
    public void logoutUser(String tokenEndpoint, String issuer, String clientId, String clientSecret, String userId) {
        try {
            String accessToken = fetchAdminAccessToken(tokenEndpoint, clientId, clientSecret);
            String logoutUri = adminUsersLogoutUri(issuer, userId);
            restClient.post().uri(logoutUri).header("Authorization", "Bearer " + accessToken).retrieve().toBodilessEntity();
        } catch (OidcDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcDiscoveryException("failed to call Keycloak admin end-session for user " + userId, e);
        }
    }

    private String fetchAdminAccessToken(String tokenEndpoint, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        String body = restClient.post().uri(tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.has("access_token")) {
                throw new OidcDiscoveryException("Keycloak admin token response is missing access_token");
            }
            return root.get("access_token").asText();
        } catch (OidcDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcDiscoveryException("malformed Keycloak admin token response", e);
        }
    }

    /** Keycloak's own convention: {@code {issuer}} is always {@code {server-root}/realms/{realm}}. */
    static String adminUsersLogoutUri(String issuer, String userId) {
        String marker = "/realms/";
        int index = issuer.indexOf(marker);
        if (index < 0) {
            throw new OidcDiscoveryException("issuer '" + issuer + "' does not look like a Keycloak realm issuer (missing " + marker + ")");
        }
        String serverRoot = issuer.substring(0, index);
        String realm = issuer.substring(index + marker.length());
        return serverRoot + "/admin/realms/" + realm + "/users/" + userId + "/logout";
    }
}
