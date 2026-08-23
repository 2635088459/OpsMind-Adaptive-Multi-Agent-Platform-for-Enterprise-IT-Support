package com.opsmind.policygovernance.infrastructure.identity;

import com.opsmind.policygovernance.application.port.IdentityAuthorizationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * SPEC-PG-014 (11-security §Permission Model): the real RBAC + ABAC
 * implementation behind {@link IdentityAuthorizationPort}, replacing the
 * fail-closed {@code StubIdentityAuthorizationAdapter} placeholder that
 * denied every approval until this spec wired an identity provider.
 *
 * <p><b>RBAC</b>: the current request's {@link Authentication} — read from
 * {@link SecurityContextHolder} rather than threaded through {@code
 * ApprovalService} as a parameter, since the port's own {@code actorId}
 * String is already all {@code ApprovalService} carries and Spring Security
 * populates the context on the same thread that later calls this adapter —
 * must carry the {@value #APPROVAL_DECIDE_AUTHORITY} OAuth2 scope (the
 * default {@code JwtAuthenticationConverter} {@code SecurityConfig} already
 * installs maps a {@code scope}/{@code scp} claim entry to a {@code SCOPE_*}
 * {@link org.springframework.security.core.GrantedAuthority}, mirroring
 * ticket-workflow-service's own {@code SCOPE_tickets:create} convention).
 * {@code approvalType} does not yet vary the required scope — no per-type
 * role exists anywhere in 11-security's own wording ("RBAC decides whether a
 * user can approve", not "approve TOOL_EXECUTION requests") — but the
 * parameter stays on the port signature for a future spec to use without
 * another interface change.
 *
 * <p><b>ABAC</b>: the token's {@value #RISK_CLEARANCE_CLAIM} claim (a list
 * of {@link RiskLevel} names) must include a level at or above the
 * request's own {@code riskLevel} — {@link RiskLevel}'s own javadoc says its
 * ordinal order "is intentionally low-to-high so callers can compare
 * severity with compareTo", which is exactly what {@link #hasRiskClearance}
 * relies on. Per-ticket/tenant/resource ABAC (11-security's other three
 * named dimensions) has no modeled attribute anywhere in this service yet —
 * {@link com.opsmind.policygovernance.domain.approval.ApprovalRequest} does
 * not carry a tenant, and no scope-resolution model exists for "resource" —
 * so only the risk-level dimension is real; adding the others is new schema
 * work for a future spec, not a false claim this one should make.
 *
 * <p>Fails closed at every step (no authentication, wrong principal, missing
 * scope, missing/unparseable claim all return {@code false}), mirroring the
 * evaluator's own fail-safe posture in {@code
 * infrastructure.evaluator.DefaultRuleEvaluatorAdapter} and the stub this
 * replaces.
 */
@Component
public class JwtIdentityAuthorizationAdapter implements IdentityAuthorizationPort {

    static final String APPROVAL_DECIDE_AUTHORITY = "SCOPE_approval:decide";
    static final String RISK_CLEARANCE_CLAIM = "risk_clearance";

    @Override
    public boolean isAuthorizedApprover(String actorId, ApprovalType approvalType, RiskLevel riskLevel) {
        Authentication authentication = currentAuthenticationFor(actorId);
        if (authentication == null) {
            return false;
        }
        return hasAuthority(authentication, APPROVAL_DECIDE_AUTHORITY) && hasRiskClearance(authentication, riskLevel);
    }

    /**
     * Simple identity inequality — the richer requester/executor/approver
     * relationship model (e.g. "the tool execution worker approving the
     * corresponding tool request") is SPEC-PG-015's own job; see this type's
     * own javadoc.
     */
    @Override
    public boolean isIndependentApprover(String requesterId, String approverId) {
        return requesterId != null && approverId != null && !requesterId.equals(approverId);
    }

    /**
     * Only trusts the security context if its principal name matches {@code
     * actorId} — the port is called with the {@code decidedBy}/actor string
     * the caller already claims, and this refuses to authorize on behalf of
     * a different principal than the one actually authenticated on this
     * thread.
     */
    private Authentication currentAuthenticationFor(String actorId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        return authentication.getName().equals(actorId) ? authentication : null;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private boolean hasRiskClearance(Authentication authentication, RiskLevel riskLevel) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return false;
        }
        List<String> clearance = jwtAuthentication.getToken().getClaimAsStringList(RISK_CLEARANCE_CLAIM);
        if (clearance == null) {
            return false;
        }
        return clearance.stream()
            .map(this::parseRiskLevel)
            .filter(Objects::nonNull)
            .anyMatch(cleared -> cleared.compareTo(riskLevel) >= 0);
    }

    private RiskLevel parseRiskLevel(String value) {
        try {
            return RiskLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
