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
}
