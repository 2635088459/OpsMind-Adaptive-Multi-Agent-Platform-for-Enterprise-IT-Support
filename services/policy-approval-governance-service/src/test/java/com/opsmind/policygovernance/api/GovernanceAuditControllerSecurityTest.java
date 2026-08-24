package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.GovernanceAuditService;
import com.opsmind.policygovernance.config.SecurityConfig;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-014 (11-security §Permission Model): "RBAC decides whether a user
 * can ... view audit." Mirrors {@link PolicyAdminControllerSecurityTest}.
 */
@WebMvcTest(GovernanceAuditController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class GovernanceAuditControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GovernanceAuditService governanceAuditService;

    @Test
    void rejectsAnUnauthenticatedAuditQuery() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit-records").param("correlationId", "corr-1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutTheAuditReadScope() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit-records")
                .param("correlationId", "corr-1")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_approval:decide"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAnAuthenticatedActorWithTheAuditReadScope() throws Exception {
        when(governanceAuditService.findByCorrelationId("corr-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/governance-audit-records")
                .param("correlationId", "corr-1")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isOk());
    }

    /** SPEC-PG-030 (goal: "queries by ticket/source/decision/approval/policy"): each new dimension is reachable through the same authorized endpoint. */
    @Test
    void allowsQueryingByEachOfTheFiveNewLinkageDimensions() throws Exception {
        when(governanceAuditService.findByTicketId("ticket-1")).thenReturn(List.of());
        when(governanceAuditService.findByApprovalRequestId("ar-1")).thenReturn(List.of());
        when(governanceAuditService.findByPolicyDecisionId("pd-1")).thenReturn(List.of());
        when(governanceAuditService.findBySourceRequestId("src-1")).thenReturn(List.of());
        when(governanceAuditService.findByPolicyId("policy-1")).thenReturn(List.of());

        for (String[] param : new String[][]{
            {"ticketId", "ticket-1"}, {"approvalRequestId", "ar-1"}, {"policyDecisionId", "pd-1"},
            {"sourceRequestId", "src-1"}, {"policyId", "policy-1"}
        }) {
            mockMvc.perform(get("/api/v1/governance-audit-records")
                    .param(param[0], param[1])
                    .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
                .andExpect(status().isOk());
        }
    }

    /** SPEC-PG-030: zero filters is a request-shape problem ({@code RequestValidationException} -&gt; {@code 400}), not a business failure. */
    @Test
    void rejectsAnAuditQueryWithNoFilterAtAll() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit-records")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isBadRequest());
    }

    /** SPEC-PG-030: more than one filter at once is equally a request-shape problem — this endpoint answers one dimension at a time, not a compound filter. */
    @Test
    void rejectsAnAuditQueryWithMoreThanOneFilterAtOnce() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit-records")
                .param("correlationId", "corr-1")
                .param("ticketId", "ticket-1")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isBadRequest());
    }
}
