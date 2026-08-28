package com.opsmind.identity.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.dto.AuthorizationDecisionView;
import com.opsmind.identity.application.dto.EvaluateAuthorizationRequest;
import com.opsmind.identity.application.dto.MyProfileView;
import com.opsmind.identity.application.dto.PrincipalContextView;
import com.opsmind.identity.application.dto.StartSessionRequest;
import com.opsmind.identity.application.dto.UserSessionView;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-UA-020 (Employee Portal Authentication Contract — 14-testing-strategy
 * §Test levels: "E2E: Employee login/self-service ticket"; "Contract:
 * 01↔Portal/API Gateway ... with consumer-driven request/response ...
 * schemas"). Unlike every prior SPEC-UA-0xx spec, this one adds no new
 * production capability — 05-api-contracts' own Employee-relevant endpoints
 * were all already real (SPEC-UA-001/005/007/014/015). What was still
 * missing was the actual end-to-end proof: a real HTTP client, a real
 * signed bearer JWT validated by the real {@code SecurityConfig#jwtDecoder}
 * (not an in-memory fake), and a real running Spring context wired to a
 * real Testcontainers Postgres — exercising the exact journey an Employee
 * Portal consumer would drive, and asserting the response SHAPE matches
 * what 05-api-contracts documents (protecting against an "unapproved
 * breaking ... diff", 14-testing-strategy's own quality-gate wording).
 *
 * <p>The bearer JWT is signed locally against a real RSA keypair, verified
 * over real HTTP against a {@link StubHttpServer}-backed discovery/JWKS
 * endpoint wired in via {@code issuer-uri} — the same pattern
 * SPEC-UA-004/006's own {@code SecurityConfigJwtDecoderTest} already
 * established, now driving the real running application instead of a
 * directly-constructed {@code JwtDecoder} bean. Role granting for the test
 * fixture uses the autowired {@code ManageRoleAssignmentUseCase} bean
 * directly rather than the real {@code POST /role-assignments} endpoint —
 * an EMPLOYEE holds no {@code identity:role:grant} permission itself
 * (SPEC-UA-011's own catalog), so self-granting via the real contract is
 * structurally impossible; this is fixture setup, not part of the
 * Employee's own contract under test.
 */
@Tag("integration")
class EmployeePortalAuthenticationContractIT extends IdentityContractTestHarness {

    @Autowired
    private ManageRoleAssignmentUseCase manageRoleAssignmentUseCase;

    private String signedJwt(String subject) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(keycloakStub.baseUrl()).subject(subject)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }

    /**
     * The Employee's own real journey: identity auto-links on first {@code
     * GET /users/me}, a fixture grant makes EMPLOYEE/SELF show up as an
     * effective role, a real session starts, a self-scoped authorization
     * request for the employee's OWN resource is ALLOWed while the exact
     * same request for someone else's resource is DENIED (SPEC-UA-015's own
     * ownership contract, proven here over real HTTP for the first time),
     * and revoke ends the session.
     */
    @Test
    void employeeCanEstablishASessionSeeItsOwnEffectiveRoleAndOnlyAccessItsOwnScopedResources() throws Exception {
        String subject = "employee-" + UUID.randomUUID();
        String token = signedJwt(subject);
        HttpHeaders headers = bearer(token);

        // 1. GET /users/me — auto-links the identity; 05-api-contracts: "Minimum profile plus effective roles/scopes."
        ResponseEntity<MyProfileView> firstProfile = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(headers), MyProfileView.class
        );
        assertThat(firstProfile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstProfile.getBody()).isNotNull();
        assertThat(firstProfile.getBody().issuer()).isEqualTo(keycloakStub.baseUrl());
        assertThat(firstProfile.getBody().subject()).isEqualTo(subject);
        assertThat(firstProfile.getBody().effectiveRoles()).isEmpty();
        String userIdentityId = firstProfile.getBody().userIdentityId();

        // Fixture: grant EMPLOYEE at SELF scope — not itself part of the Employee's own contract (see class javadoc).
        manageRoleAssignmentUseCase.grant(new GrantRoleAssignmentCommand(
            userIdentityId, "opsmind", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null),
            null, null, "test-admin", "e2e fixture", "corr-fixture"
        ));

        ResponseEntity<MyProfileView> secondProfile = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(headers), MyProfileView.class
        );
        assertThat(secondProfile.getBody().effectiveRoles()).hasSize(1);
        assertThat(secondProfile.getBody().effectiveRoles().get(0).roleCode()).isEqualTo(RoleCode.EMPLOYEE);

        // 2. POST /sessions — establishes the real session a Portal consumer would rely on for subsequent calls.
        StartSessionRequest startRequest = new StartSessionRequest("opsmind", null, null, "employee-portal", "urn:mace:acr:0", List.of("pwd"), null, 3600);
        ResponseEntity<UserSessionView> sessionResponse = restTemplate.exchange(
            baseUrl("/internal/identity/v1/sessions"), HttpMethod.POST, new HttpEntity<>(startRequest, headers), UserSessionView.class
        );
        assertThat(sessionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sessionResponse.getBody().status()).isEqualTo(SessionStatus.ACTIVE);
        String sessionId = sessionResponse.getBody().userSessionId();

        // 3. POST /tokens/introspect-context — 05-api-contracts: "returns normalized principal, assurance, session status."
        var introspectHeaders = bearer(token);
        introspectHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<PrincipalContextView> introspectResponse = restTemplate.exchange(
            baseUrl("/internal/identity/v1/tokens/introspect-context"), HttpMethod.POST,
            new HttpEntity<>("{\"userSessionId\":\"" + sessionId + "\"}", introspectHeaders), PrincipalContextView.class
        );
        assertThat(introspectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(introspectResponse.getBody().userIdentityId()).isEqualTo(userIdentityId);
        assertThat(introspectResponse.getBody().userSessionId()).isEqualTo(sessionId);
        assertThat(introspectResponse.getBody().sessionStatus()).isEqualTo(SessionStatus.ACTIVE.name());

        // 4. POST /authorization-decisions — SELF scope on the employee's OWN resource: ALLOW (SPEC-UA-014/015).
        var authHeaders = bearer(token);
        authHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        EvaluateAuthorizationRequest ownResourceRequest = new EvaluateAuthorizationRequest(
            "opsmind", userIdentityId, sessionId, "profile:read", "profile", userIdentityId,
            RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), userIdentityId, null, null
        );
        ResponseEntity<AuthorizationDecisionView> ownDecision = restTemplate.exchange(
            baseUrl("/internal/identity/v1/authorization-decisions"), HttpMethod.POST, new HttpEntity<>(ownResourceRequest, authHeaders), AuthorizationDecisionView.class
        );
        assertThat(ownDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownDecision.getBody().effect()).isEqualTo(DecisionEffect.ALLOW);

        // 5. The SAME request for someone else's resource: DENY — an Employee's own SELF scope never expands to another subject's resource.
        EvaluateAuthorizationRequest someoneElsesResourceRequest = new EvaluateAuthorizationRequest(
            "opsmind", userIdentityId, sessionId, "profile:read", "profile", "some-other-user-id",
            RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), "some-other-user-id", null, null
        );
        ResponseEntity<AuthorizationDecisionView> otherDecision = restTemplate.exchange(
            baseUrl("/internal/identity/v1/authorization-decisions"), HttpMethod.POST, new HttpEntity<>(someoneElsesResourceRequest, authHeaders), AuthorizationDecisionView.class
        );
        assertThat(otherDecision.getBody().effect()).isEqualTo(DecisionEffect.DENY);

        // 6. POST /sessions/{id}/revoke — logout.
        ResponseEntity<UserSessionView> revokeResponse = restTemplate.exchange(
            baseUrl("/internal/identity/v1/sessions/" + sessionId + "/revoke"), HttpMethod.POST, new HttpEntity<>(headers), UserSessionView.class
        );
        assertThat(revokeResponse.getBody().status()).isEqualTo(SessionStatus.REVOKED);
    }

    /** A missing/absent bearer token is rejected before ever reaching a controller — 05-api-contracts: "401 unauthenticated." */
    @Test
    void rejectsAnUnauthenticatedRequestWithoutRevealingTokenValidationInternals() {
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, HttpEntity.EMPTY, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(response.getBody()).doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("signature");
    }
}
