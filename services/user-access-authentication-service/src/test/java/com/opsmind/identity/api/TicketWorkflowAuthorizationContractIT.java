package com.opsmind.identity.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.opsmind.identity.application.dto.RegisterServiceIdentityRequest;
import com.opsmind.identity.application.dto.ServiceIdentityView;
import com.opsmind.identity.application.dto.WorkloadIdentityView;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-UA-026 (Ticket Workflow Authorization Contract — second spec of
 * phase-06 Cross-Domain Identity Contracts; SPEC-UA-025 already built its
 * sibling for domain 06). Neither named LLD section claims it in its own
 * footer — same per-spec-doc mismatch class; 14-testing-strategy is the
 * real, unnamed owner (footer range "SPEC-UA-020 through SPEC-UA-027").
 *
 * <p>ticket-workflow-service's own real 05-api-contracts (§4.1) documents
 * that its Internal APIs (API-017..022, e.g. {@code POST
 * /internal/v1/tickets/{id}/triage/start}) "use OAuth 2.0 client
 * credentials and service-specific scopes" — a genuine, real
 * client-credentials-shaped workload identity for this domain, distinct
 * from SPEC-UA-021's own human-actor "ticket submission principal"
 * contract (which was about a BROWSER-originated JWT). Checked its own
 * real code first (mirrors SPEC-UA-021/025's own discipline): {@code
 * SupportAgentDirectoryPort}'s own javadoc explicitly documents "no
 * external identity/directory service exists anywhere in this codebase"
 * for support-agent resolution — a self-documented real gap, not
 * something to invent a contract around.
 *
 * <p>Deliberately does NOT repeat SPEC-UA-025's own three tests verbatim
 * with a different service name (that would be padding) — proves two
 * genuinely different real facets of {@code ValidateWorkloadIdentityUseCase}
 * that SPEC-UA-025 never exercised: (1) a workload registered with
 * MULTIPLE allowed scopes (ticket-workflow-service's own internal APIs
 * span several, e.g. {@code tickets:triage:start}, {@code
 * tickets:classify}, {@code tickets:context:read}) is trusted by a token
 * carrying only ONE of them — real proof the match is "any one scope
 * overlaps," not "the token must carry every registered scope"; (2) the
 * real time-window half of {@code ServiceIdentity#isValid} — a workload
 * registered with an already-past {@code validUntil} is rejected even with
 * an otherwise perfect audience/scope match — over real HTTP for the first
 * time (SPEC-UA-025 only ever registered unrestricted validity).
 */
@Tag("integration")
class TicketWorkflowAuthorizationContractIT extends IdentityContractTestHarness {

    private String signedWorkloadJwt(String subject, List<String> audience, String scope) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(keycloakStub.baseUrl()).subject(subject)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
        if (audience != null) {
            claims.audience(audience);
        }
        if (scope != null) {
            claims.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }

    private ServiceIdentityView registerTicketWorkflowWorkload(String registrationToken, String subject, List<String> allowedScopes, Instant validUntil) {
        var headers = bearer(registrationToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        RegisterServiceIdentityRequest request = new RegisterServiceIdentityRequest(
            "opsmind", subject, "ticket-workflow-service", List.of("opsmind-tickets"), allowedScopes, null, validUntil
        );
        ResponseEntity<ServiceIdentityView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities"), HttpMethod.POST, new HttpEntity<>(request, headers), ServiceIdentityView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * ticket-workflow-service's own real internal APIs span several scopes
     * (tickets:triage:start, tickets:classify, tickets:context:read, ...) —
     * a real token calling just ONE internal API only ever carries that
     * one scope, never all of them at once.
     */
    @Test
    void aTicketWorkflowWorkloadRegisteredWithMultipleScopesIsTrustedByATokenCarryingOnlyOneOfThem() throws Exception {
        String subject = "ticket-workflow-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        List<String> allowedScopes = List.of("tickets:triage:start", "tickets:classify", "tickets:context:read");
        ServiceIdentityView registered = registerTicketWorkflowWorkload(registrationToken, subject, allowedScopes, null);

        String triageOnlyToken = signedWorkloadJwt(subject, List.of("opsmind-tickets"), "tickets:context:read");
        var headers = bearer(triageOnlyToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<WorkloadIdentityView> validated = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(headers), WorkloadIdentityView.class
        );

        assertThat(validated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validated.getBody().serviceIdentityId()).isEqualTo(registered.serviceIdentityId());
        assertThat(validated.getBody().status()).isEqualTo(ServiceIdentityStatus.ACTIVE);
    }

    /** The real time-window half of ServiceIdentity#isValid — an already-expired workload is never trusted, even with a perfect audience/scope match. */
    @Test
    void aTicketWorkflowWorkloadPastItsOwnValidUntilIsNeverTrustedEvenWithMatchingClaims() throws Exception {
        String subject = "ticket-workflow-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        registerTicketWorkflowWorkload(registrationToken, subject, List.of("tickets:triage:start"), Instant.now().minusSeconds(3600));

        String callToken = signedWorkloadJwt(subject, List.of("opsmind-tickets"), "tickets:triage:start");
        ResponseEntity<String> rejected = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(bearer(callToken)), String.class
        );

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("WORKLOAD_IDENTITY_NOT_TRUSTED");
    }
}
