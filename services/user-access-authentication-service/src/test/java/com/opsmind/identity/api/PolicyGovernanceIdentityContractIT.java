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
 * SPEC-UA-025 (Policy Governance Identity Contract — first spec of
 * phase-06 Cross-Domain Identity Contracts, distinct from phase-05:
 * SPEC-UA-021/024 already proved the "01<->02"/"01<->06" pairings from
 * 14-testing-strategy's own Contract row for HUMAN actors (a ticket
 * submitter, an approver) driven by their own browser-originated JWTs.
 * This phase's own three specs (UA-025/026/027, per their own sibling
 * traceability entries — 025 Policy Governance, 026 Ticket Workflow, 027
 * Runtime/Tool/Memory Service Identity) instead cover that same Contract
 * row's fourth, still-unclaimed "workload identity" surface, broken down
 * one calling domain at a time: does SPEC-UA-010's own real {@code
 * ValidateWorkloadIdentityUseCase} mechanism correctly trust (or reject) a
 * MACHINE caller shaped like that domain's own real service, as opposed to
 * a human browsing through it.
 *
 * <p>Neither of this spec's own two named LLD sections (05-api-contracts,
 * 06-event-contracts) claim it in their own footer spec-mapping lists —
 * the same per-spec-doc mismatch class as SPEC-UA-010/014/015/021/023/024.
 * 14-testing-strategy's own footer range ("SPEC-UA-020 through
 * SPEC-UA-027") is the real, unnamed-in-the-per-spec-doc owner again.
 */
@Tag("integration")
class PolicyGovernanceIdentityContractIT extends IdentityContractTestHarness {

    /** Shaped like a real client-credentials workload token: no {@code amr}/interactive-login claims, an {@code aud} and a {@code scope}. */
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

    /**
     * Self-registration: the registering caller's own verified JWT issuer/
     * subject become the new ServiceIdentity's own external subject.
     * {@code clientId} is unique per {@code tenantId} at the real DB layer
     * ({@code uq_service_identities_client}) — derived from {@code subject}
     * so each test method's own fresh registration never collides with
     * another's within the same running app/DB.
     */
    private ServiceIdentityView registerPolicyGovernanceWorkload(String registrationToken, String subject) {
        var headers = bearer(registrationToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        RegisterServiceIdentityRequest request = new RegisterServiceIdentityRequest(
            "opsmind", subject, "policy-approval-governance-service",
            List.of("opsmind-identity"), List.of("identity:workload"), null, null
        );
        ResponseEntity<ServiceIdentityView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities"), HttpMethod.POST, new HttpEntity<>(request, headers), ServiceIdentityView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<WorkloadIdentityView> validate(String token) {
        var headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(headers), WorkloadIdentityView.class
        );
    }

    /** The full round trip: register the workload, then a real client-credentials-shaped token for the same subject is trusted and recorded as seen. */
    @Test
    void aRegisteredPolicyGovernanceWorkloadIsValidatedAndRecordedAsSeen() throws Exception {
        String subject = "policy-governance-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        ServiceIdentityView registered = registerPolicyGovernanceWorkload(registrationToken, subject);
        assertThat(registered.status()).isEqualTo(ServiceIdentityStatus.ACTIVE);

        String callToken = signedWorkloadJwt(subject, List.of("opsmind-identity"), "identity:workload");
        ResponseEntity<WorkloadIdentityView> validated = validate(callToken);

        assertThat(validated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validated.getBody().serviceIdentityId()).isEqualTo(registered.serviceIdentityId());
        assertThat(validated.getBody().serviceName()).isEqualTo("policy-approval-governance-service");
        assertThat(validated.getBody().status()).isEqualTo(ServiceIdentityStatus.ACTIVE);
        assertThat(validated.getBody().validatedAt()).isNotNull();
    }

    /** 11-security: "Workloads use client credentials ... with separate audiences/scopes" — a token outside the registered allow-list is never trusted. */
    @Test
    void aTokenOutsideTheRegisteredAudienceAllowListIsRejected() throws Exception {
        String subject = "policy-governance-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        registerPolicyGovernanceWorkload(registrationToken, subject);

        String wrongAudienceToken = signedWorkloadJwt(subject, List.of("some-other-service"), "identity:workload");
        ResponseEntity<String> rejected = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(bearer(wrongAudienceToken)), String.class
        );

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("WORKLOAD_IDENTITY_NOT_TRUSTED");
    }

    /**
     * Ties SPEC-UA-010's own registration/disable lifecycle to its own
     * validation check for the first time via real HTTP: a disabled
     * ServiceIdentity is no longer trusted, even presenting an otherwise
     * perfectly matching audience/scope.
     */
    @Test
    void aDisabledPolicyGovernanceWorkloadIsNoLongerTrustedEvenWithOtherwiseValidClaims() throws Exception {
        String subject = "policy-governance-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        ServiceIdentityView registered = registerPolicyGovernanceWorkload(registrationToken, subject);

        ResponseEntity<ServiceIdentityView> disabled = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/" + registered.serviceIdentityId() + "/disable"),
            HttpMethod.POST, new HttpEntity<>(bearer(registrationToken)), ServiceIdentityView.class
        );
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody().status()).isEqualTo(ServiceIdentityStatus.DISABLED);

        String callToken = signedWorkloadJwt(subject, List.of("opsmind-identity"), "identity:workload");
        ResponseEntity<String> rejected = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(bearer(callToken)), String.class
        );

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("WORKLOAD_IDENTITY_NOT_TRUSTED");
    }
}
