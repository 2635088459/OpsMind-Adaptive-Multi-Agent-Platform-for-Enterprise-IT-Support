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
import com.opsmind.identity.application.dto.StartSessionRequest;
import com.opsmind.identity.application.dto.UserSessionView;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * SPEC-UA-022 (Support Console Authentication Contract — 14-testing-strategy
 * §Test levels: "E2E: ... Support queue scope ..."). Both named LLD
 * sections genuinely claim this spec via their own footer spec-mapping
 * lists (05-api-contracts' own footer names it directly; 14-testing-strategy's
 * own footer range "SPEC-UA-020 through SPEC-UA-027" includes it too) — no
 * per-spec-doc mismatch this time, the same clean match SPEC-UA-020 had.
 *
 * <p>Unlike SPEC-UA-020 (which only ever exercised the {@code SELF} scope),
 * this spec proves the ORGANIZATIONAL scope-coverage path
 * {@code ResourceScope#covers} (SPEC-UA-014) end to end over real HTTP for
 * the first time: a {@code SUPPORT_AGENT} granted at one
 * {@code SUPPORT_QUEUE} can access that exact queue but not a different
 * one (exact-match requirement for non-{@code TENANT} scopes), while a
 * {@code TENANT}-wide grant of the same role covers ANY queue (the
 * broader-covers-narrower rule) — and a Support persona holding no
 * matching role assignment at all is denied by default
 * (02-business-invariants #5).
 *
 * <p>Role granting for every fixture below uses the autowired {@code
 * ManageRoleAssignmentUseCase} bean directly, the same "not part of the
 * contract under test" reasoning {@code EmployeePortalAuthenticationContractIT}
 * already established (a Support Agent holds no {@code identity:role:grant}
 * permission of its own per SPEC-UA-011's catalog).
 */
@Tag("integration")
class SupportConsoleAuthenticationContractIT extends IdentityContractTestHarness {

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

    private String linkIdentity(String token) {
        ResponseEntity<MyProfileView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), MyProfileView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().userIdentityId();
    }

    private String startSession(String token) {
        StartSessionRequest request = new StartSessionRequest("opsmind", null, null, "support-console", "urn:mace:acr:0", List.of("pwd"), null, 3600);
        ResponseEntity<UserSessionView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/sessions"), HttpMethod.POST, new HttpEntity<>(request, bearer(token)), UserSessionView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().userSessionId();
    }

    private AuthorizationDecisionView evaluate(String token, String subjectId, String sessionId, String queueId) {
        var headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        EvaluateAuthorizationRequest request = new EvaluateAuthorizationRequest(
            "opsmind", subjectId, sessionId, "tickets:queue:read", "queue", queueId,
            RoleCode.SUPPORT_AGENT, new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, queueId), null, null, null
        );
        ResponseEntity<AuthorizationDecisionView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/authorization-decisions"), HttpMethod.POST, new HttpEntity<>(request, headers), AuthorizationDecisionView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** A queue-scoped grant reaches exactly its own queue, never a different one — non-TENANT scopes require an exact match (SPEC-UA-013/014). */
    @Test
    void supportAgentCanAccessItsOwnGrantedQueueButNotADifferentQueue() throws Exception {
        String token = signedJwt("support-" + UUID.randomUUID());
        String userIdentityId = linkIdentity(token);
        manageRoleAssignmentUseCase.grant(new GrantRoleAssignmentCommand(
            userIdentityId, "opsmind", RoleCode.SUPPORT_AGENT, new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "IDENTITY_SUPPORT"),
            null, null, "test-admin", "e2e fixture", "corr-fixture"
        ));

        ResponseEntity<MyProfileView> profile = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), MyProfileView.class
        );
        assertThat(profile.getBody().effectiveRoles()).hasSize(1);
        assertThat(profile.getBody().effectiveRoles().get(0).roleCode()).isEqualTo(RoleCode.SUPPORT_AGENT);

        String sessionId = startSession(token);

        AuthorizationDecisionView ownQueue = evaluate(token, userIdentityId, sessionId, "IDENTITY_SUPPORT");
        assertThat(ownQueue.effect()).isEqualTo(DecisionEffect.ALLOW);

        AuthorizationDecisionView otherQueue = evaluate(token, userIdentityId, sessionId, "SECURITY_SUPPORT");
        assertThat(otherQueue.effect()).isEqualTo(DecisionEffect.DENY);

        ResponseEntity<UserSessionView> revoke = restTemplate.exchange(
            baseUrl("/internal/identity/v1/sessions/" + sessionId + "/revoke"), HttpMethod.POST, new HttpEntity<>(bearer(token)), UserSessionView.class
        );
        assertThat(revoke.getBody().status().name()).isEqualTo("REVOKED");
    }

    /** A TENANT-wide grant of the same role covers ANY queue (SPEC-UA-014's broader-covers-narrower rule), exercised end to end for the first time. */
    @Test
    void aTenantWideSupportAgentGrantCoversAnyQueue() throws Exception {
        String token = signedJwt("support-" + UUID.randomUUID());
        String userIdentityId = linkIdentity(token);
        manageRoleAssignmentUseCase.grant(new GrantRoleAssignmentCommand(
            userIdentityId, "opsmind", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(),
            null, null, "test-admin", "e2e fixture", "corr-fixture"
        ));
        String sessionId = startSession(token);

        AuthorizationDecisionView anyQueue = evaluate(token, userIdentityId, sessionId, "WHATEVER_QUEUE");

        assertThat(anyQueue.effect()).isEqualTo(DecisionEffect.ALLOW);
    }

    /** Deny by default (02-business-invariants #5): a Support persona holding no matching role assignment at all is denied. */
    @Test
    void aSupportPersonaWithNoRoleAssignmentIsDeniedByDefault() throws Exception {
        String token = signedJwt("support-" + UUID.randomUUID());
        String userIdentityId = linkIdentity(token);
        String sessionId = startSession(token);

        AuthorizationDecisionView decision = evaluate(token, userIdentityId, sessionId, "IDENTITY_SUPPORT");

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(decision.reasonCodes()).contains("NO_MATCHING_ROLE_ASSIGNMENT");
    }
}
