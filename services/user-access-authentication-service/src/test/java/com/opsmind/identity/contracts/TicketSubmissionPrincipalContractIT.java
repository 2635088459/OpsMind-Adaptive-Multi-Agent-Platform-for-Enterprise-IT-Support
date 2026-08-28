package com.opsmind.identity.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-UA-021 (Ticket Submission Principal Contract). Neither of this
 * spec's own two named LLD sections (05-api-contracts, 06-event-contracts)
 * actually claim it in their own footer spec-mapping lists — the same
 * per-spec-doc mismatch class as SPEC-UA-010/014/015. The section that DOES
 * claim it (never named in the per-spec doc at all, the same "bonus
 * section" class as SPEC-UA-012/019) is 14-testing-strategy, whose own
 * "Contract" test-level row names "01<->02 ... with consumer-driven
 * request/response/event schemas" and whose own footer range
 * ("SPEC-UA-020 through SPEC-UA-027") includes this spec.
 *
 * <p>02-ticket-workflow's own 06-event-contracts consumed-event catalog
 * (CON-001..014) names zero {@code identity.*} events — domain 02 never
 * consumes anything domain 01 publishes asynchronously. Its own
 * 05-api-contracts instead states plainly, for {@code POST /api/v1/tickets}:
 * "For an employee request, requesterId comes from the JWT" — and its own
 * real, checked-in {@code PublicTicketController#createTicket} confirms
 * this literally: {@code jwt.getSubject()}, an {@code azp}/{@code client_id}
 * claim, and a {@code scope} claim, read directly off the SAME access token
 * both services independently validate as their own resource server (no
 * synchronous call from domain 02 to domain 01 exists for ticket
 * submission at all). So the real, honest scope of a "01<->02 ticket
 * submission" contract, buildable entirely within THIS spec's own
 * {@code service: user-access-authentication-service} without reaching
 * into another domain's own repository, is: proving domain 01's real,
 * running JWT acceptance and principal-normalization endpoints honor
 * exactly the claim contract 02's real code depends on, and never
 * secretly transform or gate on anything outside that contract.
 *
 * <p>{@link #ticketSubmissionPrincipalSchema} is independently authored
 * from 02's own checked-in code (not imported from anything this service
 * itself produces) — the same "don't import the producer's own schema"
 * discipline {@code memory-knowledge-service}'s own {@code
 * tests/contracts/schemas.py} already established for this monorepo.
 */
@Tag("integration")
class TicketSubmissionPrincipalContractIT extends IdentityContractTestHarness {

    private static final String SCHEMA_LOCATION = "contracts/ticket-submission-principal-v1.schema.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSchema ticketSubmissionPrincipalSchema() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("contract schema not found on classpath: " + SCHEMA_LOCATION);
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load contract schema: " + SCHEMA_LOCATION, e);
        }
    }

    private String signedJwt(String subject, String azp, Object scope) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(keycloakStub.baseUrl()).subject(subject)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (azp != null) {
            claims.claim("azp", azp);
        }
        if (scope != null) {
            claims.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }

    /**
     * The exact claim set a real ticket-submission caller presents — signed
     * the same way SPEC-UA-005's own real browser login would produce it —
     * satisfies 02's own real contract BEFORE it ever reaches domain 01,
     * and domain 01's real {@code jwtDecoder} (SPEC-UA-004/006) still
     * accepts that same token over real HTTP. A contract test that only
     * checked one side would miss a real divergence (e.g. domain 01
     * rejecting an algorithm/audience domain 02 would have accepted, or
     * vice versa).
     */
    @Test
    void aRealTicketSubmissionAccessTokenSatisfiesBothTheContractSchemaAndDomain01sOwnAcceptance() throws Exception {
        String subject = "employee-" + UUID.randomUUID();
        String token = signedJwt(subject, "opsmind-portal", "tickets:create tickets:read");

        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        JsonNode claimsNode = OBJECT_MAPPER.valueToTree(claims.getClaims());
        Set<ValidationMessage> errors = ticketSubmissionPrincipalSchema().validate(claimsNode);
        assertThat(errors).as("a real ticket-submission access token must satisfy 02's own principal contract: %s", errors).isEmpty();

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Which OAuth scopes an access token carries is Keycloak/domain-02's
     * own authorization vocabulary, never a condition on domain 01's own
     * JWT acceptance (02-business-invariants: domain boundaries; mirrors
     * SPEC-UA-012's own domain-01-vs-domain-06 discipline). A token that
     * would never satisfy 02's own {@code SCOPE_tickets:create} authority
     * check must still be perfectly valid to domain 01.
     */
    @Test
    void domain01AcceptsARealTokenEvenWithoutAnyTicketSpecificScope() throws Exception {
        String token = signedJwt("employee-" + UUID.randomUUID(), "opsmind-portal", null);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The crux of the contract: 02's own {@code PublicTicketController}
     * derives {@code requesterId} as {@code jwt.getSubject()} directly, with
     * zero call to domain 01. This only stays safe as long as domain 01's
     * own normalized {@code subject} (SPEC-UA-007's {@code
     * PrincipalContextView}) is byte-for-byte the same value — never a
     * mapped/prefixed/internal id substituted in its place. Asserted
     * against the RAW parsed HTTP JSON response, not domain 01's own DTO
     * class, so a real field rename/remap shows up as a failure here
     * instead of two copies of the same assumption agreeing with each
     * other (memory-knowledge-service's own {@code tests/contracts}
     * precedent).
     */
    @Test
    void domain01sNormalizedSubjectIsByteForByteTheSameRequesterIdTicketSubmissionWouldDerive() throws Exception {
        String subject = "employee-" + UUID.randomUUID();
        String token = signedJwt(subject, "opsmind-portal", "tickets:create");
        HttpHeaders headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/tokens/introspect-context"), HttpMethod.POST, new HttpEntity<>("{}", headers), String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = OBJECT_MAPPER.readTree(response.getBody());
        assertThat(body.get("subject").asText()).isEqualTo(subject);
        assertThat(body.get("issuer").asText()).isEqualTo(keycloakStub.baseUrl());
    }
}
