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
import com.opsmind.identity.application.dto.MyProfileView;
import com.opsmind.identity.support.IdentityContractTestHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-UA-024 (User Resolution Confirmation Contract). Neither of this
 * spec's own two named LLD sections (05-api-contracts, 06-event-contracts)
 * actually claim it in their own footer spec-mapping lists — the same
 * per-spec-doc mismatch class as SPEC-UA-010/014/015/021/023. The section
 * that DOES claim it (never named in the per-spec doc, the same "bonus
 * section" class as SPEC-UA-012/019/021/023) is 14-testing-strategy, whose
 * own footer range ("SPEC-UA-020 through SPEC-UA-027") includes it and
 * whose own "Contract" test-level row names "01<->Portal/API Gateway,
 * 01<->02, 01<->06, and workload identity" — SPEC-UA-021 already built the
 * "01<->02" half; this is the "01<->06" half.
 *
 * <p>Checked 06-policy-approval-governance's own real, checked-in code
 * directly (the same discipline SPEC-UA-021 established for
 * ticket-workflow-service) rather than guessing: {@code
 * GovernanceRequestContext#actorId} reads {@code Authentication#getName()}
 * directly (the JWT's own {@code sub}), with zero synchronous call to
 * domain 01, exactly mirroring domain 02's own {@code
 * PublicTicketController#createTicket}. {@code
 * JwtIdentityAuthorizationAdapter#isIndependentApprover(requesterId,
 * approverId)} then does nothing more than compare two such actor strings
 * by plain inequality — "user resolution confirmation," concretely: domain
 * 06's entire separation-of-duties guarantee depends on domain 01's own
 * subject identity being STABLE (the same real external subject always
 * resolves to the same value) and NON-COLLIDING (two different real
 * external subjects never resolve to the same value), never on any call
 * back to domain 01 to actually confirm it. Tests 1/2 mirror
 * SPEC-UA-021's own provider-side claim-shape/domain-boundary proof
 * (independently authored schema, domain 01 never gated on another
 * domain's own scope/claim vocabulary); tests 3/4 are this spec's own new
 * content — proving the stability/non-collision property domain 06's real
 * code actually depends on.
 */
@Tag("integration")
class UserResolutionConfirmationContractIT extends IdentityContractTestHarness {

    private static final String SCHEMA_LOCATION = "contracts/approval-decision-actor-v1.schema.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSchema approvalDecisionActorSchema() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("contract schema not found on classpath: " + SCHEMA_LOCATION);
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load contract schema: " + SCHEMA_LOCATION, e);
        }
    }

    private String signedJwt(String subject, Object scope, List<String> riskClearance) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(keycloakStub.baseUrl()).subject(subject)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
        if (scope != null) {
            claims.claim("scope", scope);
        }
        if (riskClearance != null) {
            claims.claim("risk_clearance", riskClearance);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }

    private MyProfileView linkIdentity(String token) {
        ResponseEntity<MyProfileView> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), MyProfileView.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * A real approval-decision access token satisfies domain 06's own
     * checked-in claim contract BEFORE it ever reaches domain 01, and
     * domain 01's own real jwtDecoder still accepts it over real HTTP.
     */
    @Test
    void aRealApprovalDecisionActorTokenSatisfiesBothTheContractSchemaAndDomain01sOwnAcceptance() throws Exception {
        String subject = "approver-" + UUID.randomUUID();
        String token = signedJwt(subject, "approval:decide", List.of("HIGH", "MEDIUM"));

        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        JsonNode claimsNode = OBJECT_MAPPER.valueToTree(claims.getClaims());
        Set<ValidationMessage> errors = approvalDecisionActorSchema().validate(claimsNode);
        assertThat(errors).as("a real approval-decision access token must satisfy 06's own actor contract: %s", errors).isEmpty();

        assertThat(linkIdentity(token).subject()).isEqualTo(subject);
    }

    /**
     * Which OAuth scopes/claims an access token carries is Keycloak/domain-06's
     * own authorization vocabulary, never a condition on domain 01's own
     * JWT acceptance (mirrors SPEC-UA-021's identical domain-boundary
     * discipline for domain 02).
     */
    @Test
    void domain01AcceptsARealTokenEvenWithoutAnyApprovalSpecificScopeOrRiskClearanceClaim() throws Exception {
        String token = signedJwt("approver-" + UUID.randomUUID(), null, null);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl("/internal/identity/v1/users/me"), HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The crux of "resolution confirmation," part one: {@code
     * JwtIdentityAuthorizationAdapter#isIndependentApprover} only works if
     * the SAME real external subject always resolves to the SAME identity
     * across separate tokens/sessions — two independently signed tokens for
     * the identical {@code sub} must resolve to the identical {@code
     * userIdentityId} and normalized {@code subject}, never a fresh/
     * different one.
     */
    @Test
    void domain01sResolutionIsStableAcrossSeparateTokensForTheSameExternalSubject() throws Exception {
        String subject = "approver-" + UUID.randomUUID();
        String firstToken = signedJwt(subject, "approval:decide", List.of("LOW"));
        String secondToken = signedJwt(subject, "approval:decide", List.of("LOW"));

        MyProfileView first = linkIdentity(firstToken);
        MyProfileView second = linkIdentity(secondToken);

        assertThat(second.userIdentityId()).isEqualTo(first.userIdentityId());
        assertThat(second.subject()).isEqualTo(first.subject()).isEqualTo(subject);
    }

    /**
     * The crux of "resolution confirmation," part two: {@code
     * isIndependentApprover}'s own separation-of-duties guarantee is only
     * meaningful if two DIFFERENT real external subjects are never confused
     * with each other — distinct {@code sub} claims must always resolve to
     * distinct {@code userIdentityId}s.
     */
    @Test
    void domain01NeverConfusesTwoDifferentExternalSubjectsWithEachOther() throws Exception {
        String requesterSubject = "requester-" + UUID.randomUUID();
        String approverSubject = "approver-" + UUID.randomUUID();
        String requesterToken = signedJwt(requesterSubject, "approval:request", null);
        String approverToken = signedJwt(approverSubject, "approval:decide", List.of("HIGH"));

        MyProfileView requester = linkIdentity(requesterToken);
        MyProfileView approver = linkIdentity(approverToken);

        assertThat(requester.userIdentityId()).isNotEqualTo(approver.userIdentityId());
        assertThat(requester.subject()).isNotEqualTo(approver.subject());
    }
}
