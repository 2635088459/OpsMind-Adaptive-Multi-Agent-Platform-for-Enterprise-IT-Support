package com.opsmind.policygovernance.infrastructure.identity;

import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-PG-014 (11-security §Permission Model). Verifies both halves in
 * isolation: RBAC (the {@value JwtIdentityAuthorizationAdapter#APPROVAL_DECIDE_AUTHORITY}
 * scope) and ABAC (the {@value JwtIdentityAuthorizationAdapter#RISK_CLEARANCE_CLAIM}
 * claim compared against {@link RiskLevel}'s own low-to-high ordinal order),
 * plus the fail-closed defaults a real production request without either
 * must fall back to.
 */
@Tag("unit")
class JwtIdentityAuthorizationAdapterTest {

    private final JwtIdentityAuthorizationAdapter adapter = new JwtIdentityAuthorizationAdapter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorizesAnActorWithTheApprovalDecideScopeAndSufficientRiskClearance() {
        authenticateAsJwt("approver-1", List.of("approval:decide"), Map.of("risk_clearance", List.of("HIGH", "CRITICAL")));

        boolean authorized = adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH);

        assertThat(authorized).isTrue();
    }

    @Test
    void aRiskClearanceAtAHigherLevelThanTheRequestIsSufficient() {
        authenticateAsJwt("approver-1", List.of("approval:decide"), Map.of("risk_clearance", List.of("CRITICAL")));

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isTrue();
    }

    @Test
    void deniesAnActorWithoutTheApprovalDecideScopeEvenWithRiskClearance() {
        authenticateAsJwt("approver-1", List.of("some:other:scope"), Map.of("risk_clearance", List.of("CRITICAL")));

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isFalse();
    }

    @Test
    void deniesAnActorWithTheScopeButInsufficientRiskClearance() {
        authenticateAsJwt("approver-1", List.of("approval:decide"), Map.of("risk_clearance", List.of("LOW", "MEDIUM")));

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH)).isFalse();
    }

    @Test
    void deniesAnActorWithNoRiskClearanceClaimAtAll() {
        authenticateAsJwt("approver-1", List.of("approval:decide"), Map.of());

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isFalse();
    }

    /** The port is only ever asked to authorize the principal already authenticated on this thread. */
    @Test
    void deniesWhenTheAuthenticatedPrincipalDoesNotMatchTheRequestedActorId() {
        authenticateAsJwt("someone-else", List.of("approval:decide"), Map.of("risk_clearance", List.of("CRITICAL")));

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isFalse();
    }

    @Test
    void deniesWhenThereIsNoAuthenticatedSecurityContextAtAll() {
        SecurityContextHolder.clearContext();

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isFalse();
    }

    /** A non-JWT Authentication (e.g. the standalone-MockMvc TestingAuthenticationToken other tests use) has no claims to check. */
    @Test
    void deniesForANonJwtAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "approver-1", null, List.of(new SimpleGrantedAuthority(JwtIdentityAuthorizationAdapter.APPROVAL_DECIDE_AUTHORITY))
        ));

        assertThat(adapter.isAuthorizedApprover("approver-1", ApprovalType.TOOL_EXECUTION, RiskLevel.LOW)).isFalse();
    }

    @Test
    void isIndependentApproverIsTrueOnlyForADifferentActor() {
        assertThat(adapter.isIndependentApprover("requester-1", "approver-1")).isTrue();
        assertThat(adapter.isIndependentApprover("requester-1", "requester-1")).isFalse();
    }

    private void authenticateAsJwt(String subject, List<String> scopes, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("scope", String.join(" ", scopes));
        claims.putAll(extraClaims);
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claims(c -> c.putAll(claims))
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
        List<SimpleGrantedAuthority> authorities = scopes.stream()
            .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities, subject));
    }
}
