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
import com.opsmind.identity.application.dto.RequestStepUpChallengeRequest;
import com.opsmind.identity.application.dto.StartSessionRequest;
import com.opsmind.identity.application.dto.StepUpChallengeView;
import com.opsmind.identity.application.dto.UserSessionView;
import com.opsmind.identity.application.dto.VerifyStepUpChallengeRequest;
import com.opsmind.identity.application.dto.ConsumeStepUpChallengeRequest;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.stepup.StepUpStatus;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-UA-023 (Approval Center Authentication Contract — 14-testing-strategy
 * §Test levels: "E2E: ... Approver step-up ..."). 11-security's own footer
 * does NOT claim this spec despite being named in the per-spec doc's own
 * `lld_mapping` — the same per-spec-doc mismatch class as
 * SPEC-UA-010/014/015/021. Real mapping: 05-api-contracts (footer names it
 * directly) + 14-testing-strategy (footer range "SPEC-UA-020 through
 * SPEC-UA-027" includes it, unnamed in the per-spec doc — the same "bonus
 * section" class as SPEC-UA-012/019/021).
 *
 * <p>Unlike SPEC-UA-020 (SELF ownership) and SPEC-UA-022 (SUPPORT_QUEUE/
 * TENANT organizational coverage), this spec proves the ASSURANCE leg
 * (SPEC-UA-016/017/018) end to end over real HTTP for the first time: an
 * Approver's session starts with low assurance, an authorization decision
 * requiring a higher level correctly returns {@code REQUIRE_STEP_UP} (not
 * {@code DENY} — SPEC-UA-016's own distinct third outcome), and the real
 * step-up challenge request/verify/consume round trip (SPEC-UA-018) then
 * produces a one-time-consumed proof. Consuming a step-up challenge does
 * NOT retroactively elevate the session's own stored assurance anywhere in
 * this domain's real model ({@code ManageStepUpService} never touches
 * {@code UserSessionRepository} at all) — 11-security's own literal
 * wording ("opaque handle/hash bound to action/resource/session and
 * consumed once") is a per-action proof, not a session-wide upgrade, so
 * this test does not re-call {@code /authorization-decisions} a second
 * time expecting a different answer; that would assert behavior no LLD
 * section or real code actually implements.
 */
@Tag("integration")
class ApprovalCenterAuthenticationContractIT extends IdentityContractTestHarness {

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

    private String linkIdentityAndGrantApprover(String token) {
        ResponseEntity<MyProfileView> profile = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), MyProfileView.class
        );
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        String userIdentityId = profile.getBody().userIdentityId();

        manageRoleAssignmentUseCase.grant(new GrantRoleAssignmentCommand(
            userIdentityId, "opsmind", RoleCode.APPROVER, ResourceScope.tenantWide(),
            null, null, "test-admin", "e2e fixture", "corr-fixture"
        ));
        return userIdentityId;
    }

    /** Deliberately LOW assurance (single-factor password) — the whole point is that it does not satisfy the step-up requirement yet. */
    private String startLowAssuranceSession(String token) {
        StartSessionRequest request = new StartSessionRequest("opsmind", null, null, "approval-center", "urn:mace:acr:0", List.of("pwd"), null, 3600);
        ResponseEntity<UserSessionView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/sessions"), HttpMethod.POST, new HttpEntity<>(request, bearer(token)), UserSessionView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().userSessionId();
    }

    private AuthorizationDecisionView evaluateApprovalDecision(String token, String subjectId, String sessionId, String resourceId) {
        var headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        EvaluateAuthorizationRequest request = new EvaluateAuthorizationRequest(
            "opsmind", subjectId, sessionId, "approval:decide", "approval", resourceId,
            RoleCode.APPROVER, ResourceScope.tenantWide(), null, "AAL2", List.of("otp")
        );
        ResponseEntity<AuthorizationDecisionView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/authorization-decisions"), HttpMethod.POST, new HttpEntity<>(request, headers), AuthorizationDecisionView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<StepUpChallengeView> requestStepUp(String token, String sessionId, String resourceId) {
        var headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        RequestStepUpChallengeRequest request = new RequestStepUpChallengeRequest(
            sessionId, "approval:decide", "approval", resourceId, "AAL2", List.of("otp"), 3, 300
        );
        return restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges"), HttpMethod.POST, new HttpEntity<>(request, headers), StepUpChallengeView.class
        );
    }

    private String extractNonce(String redirect) {
        return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("nonce");
    }

    /**
     * The full round trip: insufficient assurance -> REQUIRE_STEP_UP ->
     * request a real challenge -> verify with genuine re-authentication
     * evidence -> consume exactly once, a second consume rejected.
     */
    @Test
    void approverStepUpRoundTripElevatesFromRequireStepUpToAOneTimeConsumedProof() throws Exception {
        String subject = "approver-" + UUID.randomUUID();
        String token = signedJwt(subject);
        String userIdentityId = linkIdentityAndGrantApprover(token);
        String sessionId = startLowAssuranceSession(token);

        AuthorizationDecisionView beforeStepUp = evaluateApprovalDecision(token, userIdentityId, sessionId, "apr-1");
        assertThat(beforeStepUp.effect()).isEqualTo(DecisionEffect.REQUIRE_STEP_UP);
        assertThat(beforeStepUp.assuranceLevel()).isEqualTo("urn:mace:acr:0");

        ResponseEntity<StepUpChallengeView> requested = requestStepUp(token, sessionId, "apr-1");
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        StepUpChallengeView challenge = requested.getBody();
        assertThat(challenge.status()).isEqualTo(StepUpStatus.PENDING);
        assertThat(challenge.redirect()).isNotBlank();
        String nonce = extractNonce(challenge.redirect());
        assertThat(nonce).isNotBlank();

        var verifyHeaders = bearer(token);
        verifyHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        VerifyStepUpChallengeRequest verifyRequest = new VerifyStepUpChallengeRequest(keycloakStub.baseUrl(), subject, "AAL2", List.of("otp"), nonce);
        ResponseEntity<StepUpChallengeView> verified = restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges/" + challenge.stepUpChallengeId() + "/verify"),
            HttpMethod.POST, new HttpEntity<>(verifyRequest, verifyHeaders), StepUpChallengeView.class
        );
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().status()).isEqualTo(StepUpStatus.VERIFIED);
        assertThat(verified.getBody().verifiedAt()).isNotNull();

        var consumeHeaders = bearer(token);
        consumeHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ConsumeStepUpChallengeRequest consumeRequest = new ConsumeStepUpChallengeRequest("approval:decide", "approval", "apr-1");
        ResponseEntity<StepUpChallengeView> consumed = restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges/" + challenge.stepUpChallengeId() + "/consume"),
            HttpMethod.POST, new HttpEntity<>(consumeRequest, consumeHeaders), StepUpChallengeView.class
        );
        assertThat(consumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consumed.getBody().status()).isEqualTo(StepUpStatus.CONSUMED);
        assertThat(consumed.getBody().consumedAt()).isNotNull();

        // 11-security: "consumed once" — a second consume of the same proof is rejected, not silently re-honored.
        ResponseEntity<String> secondConsume = restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges/" + challenge.stepUpChallengeId() + "/consume"),
            HttpMethod.POST, new HttpEntity<>(consumeRequest, consumeHeaders), String.class
        );
        assertThat(secondConsume.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Real HTTP proof of a mandatory security case (14-testing-strategy
     * §Mandatory security cases: "token substitution/replay ..., step-up
     * ... double consumption"): evidence carrying the wrong nonce is
     * rejected outright, never silently accepted as proof for a different
     * re-authentication. Also the real regression test for the
     * {@code StepUpEvidenceRejectedException} -> 500 gap this spec found
     * and fixed in {@code GlobalRestExceptionHandler}.
     */
    @Test
    void stepUpEvidenceWithAWrongNonceIsRejectedNotSilentlyAccepted() throws Exception {
        String subject = "approver-" + UUID.randomUUID();
        String token = signedJwt(subject);
        linkIdentityAndGrantApprover(token);
        String sessionId = startLowAssuranceSession(token);

        ResponseEntity<StepUpChallengeView> requested = requestStepUp(token, sessionId, "apr-2");
        String challengeId = requested.getBody().stepUpChallengeId();

        var verifyHeaders = bearer(token);
        verifyHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        VerifyStepUpChallengeRequest wrongEvidence = new VerifyStepUpChallengeRequest(keycloakStub.baseUrl(), subject, "AAL2", List.of("otp"), "this-is-not-the-real-nonce");
        ResponseEntity<String> rejected = restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges/" + challengeId + "/verify"),
            HttpMethod.POST, new HttpEntity<>(wrongEvidence, verifyHeaders), String.class
        );

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).contains("STEPUP_EVIDENCE_REJECTED");

        ResponseEntity<StepUpChallengeView> stillPending = restTemplate.exchange(
            baseUrl("/internal/identity/v1/step-up/challenges/" + challengeId), HttpMethod.GET, new HttpEntity<>(bearer(token)), StepUpChallengeView.class
        );
        assertThat(stillPending.getBody().status()).isEqualTo(StepUpStatus.PENDING);
        assertThat(stillPending.getBody().attemptCount()).isEqualTo(1);
    }
}
