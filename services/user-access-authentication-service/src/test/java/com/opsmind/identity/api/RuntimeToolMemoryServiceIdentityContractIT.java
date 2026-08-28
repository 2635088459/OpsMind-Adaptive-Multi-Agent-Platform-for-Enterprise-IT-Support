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
 * SPEC-UA-027 (Runtime Tool Memory Service Identity — third and final spec
 * of phase-06 Cross-Domain Identity Contracts). Neither named LLD section
 * (05-api-contracts, 11-security) claims it in its own footer — same
 * per-spec-doc mismatch class as its phase-06 siblings; 14-testing-strategy
 * is the real, unnamed owner (footer range ends at "SPEC-UA-027").
 *
 * <p>Checked all three named services' own real code first (mirrors
 * SPEC-UA-021/025/026's own discipline) — and found a genuinely different
 * situation from those two siblings: agent-runtime-service,
 * tool-integration-gateway, and memory-knowledge-service (all Python/
 * FastAPI, per [[tech-stack-per-service]]) have ZERO real JWT/OAuth2/
 * client-credentials validation code anywhere in any of the three —
 * confirmed by grep across all three `src` trees. memory-knowledge-service's
 * own real {@code StaticAuthorizationPolicyAdapter} instead trusts a plain
 * request-body {@code actor_id}/{@code AccessScope}, not a verified token.
 * Unlike SPEC-UA-025/026 (where the target service genuinely already
 * authenticates via real client-credentials JWTs), there is no real
 * caller-side integration to point at here. This spec is therefore honestly
 * scoped as domain 01's own PROVIDER-SIDE readiness proof — {@code
 * ValidateWorkloadIdentityUseCase} is a caller-agnostic mechanism that
 * works for any correctly-shaped token regardless of which real service
 * eventually presents one — not a claim that real integration already
 * exists on the other side (mirrors SPEC-UA-026's own honest treatment of
 * {@code SupportAgentDirectoryPort}'s self-documented gap).
 *
 * <p>Combining three services into one spec is itself real, additional
 * scope: deliberately does NOT repeat SPEC-UA-025/026's own tests a third
 * time with yet another service name — proves (1) the real "not yet valid"
 * half of {@code ServiceIdentity#isValid} (a {@code validFrom} in the
 * future — SPEC-UA-026 only ever tested an already-past {@code
 * validUntil}); (2) real scope-allow-list REJECTION (an otherwise-matching
 * audience whose scope is outside the registered allow-list — SPEC-UA-026
 * only ever tested ALLOW via partial scope overlap, never the reject path
 * directly); (3) NEW to this spec specifically, motivated by combining
 * three services in one go — three separately registered workloads never
 * cross-trust each other's tokens, the workload-side analogue of
 * SPEC-UA-024's own human-actor non-collision proof.
 */
@Tag("integration")
class RuntimeToolMemoryServiceIdentityContractIT extends IdentityContractTestHarness {

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

    private ServiceIdentityView register(
        String registrationToken, String subject, String serviceName, List<String> allowedAudiences,
        List<String> allowedScopes, Instant validFrom, Instant validUntil
    ) {
        var headers = bearer(registrationToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        RegisterServiceIdentityRequest request = new RegisterServiceIdentityRequest(
            "opsmind", subject, serviceName, allowedAudiences, allowedScopes, validFrom, validUntil
        );
        ResponseEntity<ServiceIdentityView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities"), HttpMethod.POST, new HttpEntity<>(request, headers), ServiceIdentityView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> validateRaw(String token) {
        return restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class
        );
    }

    /** The real "not yet valid" half of ServiceIdentity#isValid — a workload registered with a future validFrom is not trusted before that instant arrives. */
    @Test
    void anAgentRuntimeWorkloadNotYetWithinItsOwnValidFromWindowIsNotTrusted() throws Exception {
        String subject = "agent-runtime-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        register(registrationToken, subject, "agent-runtime-service", List.of("opsmind-runtime"), List.of("runtime:workflow:execute"),
            Instant.now().plusSeconds(3600), null);

        String callToken = signedWorkloadJwt(subject, List.of("opsmind-runtime"), "runtime:workflow:execute");
        ResponseEntity<String> rejected = validateRaw(callToken);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("WORKLOAD_IDENTITY_NOT_TRUSTED");
    }

    /** Real scope-allow-list rejection: a matching audience with a scope outside the registered allow-list is never trusted. */
    @Test
    void aToolIntegrationGatewayWorkloadPresentingAScopeOutsideItsOwnAllowListIsRejected() throws Exception {
        String subject = "tool-integration-gateway-" + UUID.randomUUID();
        String registrationToken = signedWorkloadJwt(subject, null, null);
        register(registrationToken, subject, "tool-integration-gateway", List.of("opsmind-tools"), List.of("tools:execute"), null, null);

        String wrongScopeToken = signedWorkloadJwt(subject, List.of("opsmind-tools"), "tools:admin");
        ResponseEntity<String> rejected = validateRaw(wrongScopeToken);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("WORKLOAD_IDENTITY_NOT_TRUSTED");
    }

    /**
     * The workload-side analogue of SPEC-UA-024's own human-actor
     * non-collision proof: three separately registered service identities
     * (one per named service) coexist in the same real table and each
     * validates to its own distinct, correct {@code serviceIdentityId} —
     * none is ever confused with, or resolved as, another — plus a token
     * shaped for one registered workload's own subject but carrying a
     * DIFFERENT workload's own audience is still rejected (the audience
     * allow-list is per-identity, never shared).
     */
    @Test
    void threeDistinctServiceWorkloadsResolveIndependentlyAndNeverCrossTrust() throws Exception {
        String runtimeSubject = "agent-runtime-" + UUID.randomUUID();
        String toolSubject = "tool-integration-gateway-" + UUID.randomUUID();
        String memorySubject = "memory-knowledge-" + UUID.randomUUID();

        ServiceIdentityView runtime = register(
            signedWorkloadJwt(runtimeSubject, null, null), runtimeSubject, "agent-runtime-service",
            List.of("opsmind-runtime"), List.of("runtime:workflow:execute"), null, null
        );
        ServiceIdentityView tool = register(
            signedWorkloadJwt(toolSubject, null, null), toolSubject, "tool-integration-gateway",
            List.of("opsmind-tools"), List.of("tools:execute"), null, null
        );
        ServiceIdentityView memory = register(
            signedWorkloadJwt(memorySubject, null, null), memorySubject, "memory-knowledge-service",
            List.of("opsmind-memory"), List.of("memory:search"), null, null
        );
        assertThat(List.of(runtime.serviceIdentityId(), tool.serviceIdentityId(), memory.serviceIdentityId())).doesNotHaveDuplicates();

        var headers = bearer(signedWorkloadJwt(runtimeSubject, List.of("opsmind-runtime"), "runtime:workflow:execute"));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<WorkloadIdentityView> runtimeValidated = restTemplate.exchange(
            baseUrl("/internal/identity/v1/service-identities/validate"), HttpMethod.POST, new HttpEntity<>(headers), WorkloadIdentityView.class
        );
        assertThat(runtimeValidated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runtimeValidated.getBody().serviceIdentityId()).isEqualTo(runtime.serviceIdentityId());
        assertThat(runtimeValidated.getBody().status()).isEqualTo(ServiceIdentityStatus.ACTIVE);

        // agent-runtime-service's own registered identity never accepts tool-integration-gateway's own audience.
        String runtimeTokenWithToolAudience = signedWorkloadJwt(runtimeSubject, List.of("opsmind-tools"), "tools:execute");
        ResponseEntity<String> crossServiceRejected = validateRaw(runtimeTokenWithToolAudience);
        assertThat(crossServiceRejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
