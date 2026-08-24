package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.OutboxDispatchService;
import com.opsmind.policygovernance.application.PolicyDecisionService;
import com.opsmind.policygovernance.application.RecoveryService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-033 (goal: "startup recovery workers", "poison decision review").
 * Mirrors {@code GovernanceAuditComplianceControllerSecurityTest}'s own
 * shape: {@code :run} follows {@code OutboxAdminController}'s
 * baseline-authenticated-only precedent, {@code /poison-decisions} follows
 * {@code GovernanceAuditController}'s own audit-read-scope precedent.
 */
@WebMvcTest(RecoveryController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class RecoveryControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecoveryService recoveryService;

    @MockitoBean
    private PolicyDecisionService policyDecisionService;

    @Test
    void rejectsAnUnauthenticatedRecoveryRunRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/recovery:run"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAnyAuthenticatedActorToRunRecovery() throws Exception {
        when(recoveryService.runRecovery()).thenReturn(new RecoveryService.RecoveryReport(
            new OutboxDispatchService.DrainResult(2, 0, 1), 3, List.of(), 1, List.of("outbox-dead")
        ));

        mockMvc.perform(post("/api/v1/admin/recovery:run")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_nothing:relevant"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outboxDispatch.published").value(2))
            .andExpect(jsonPath("$.outboxDispatch.deadLettered").value(1))
            .andExpect(jsonPath("$.expiredApprovalsCount").value(3))
            .andExpect(jsonPath("$.poisonDecisionCount").value(1))
            .andExpect(jsonPath("$.deadLetteredOutboxIds[0]").value("outbox-dead"));
    }

    @Test
    void rejectsAnUnauthenticatedPoisonDecisionsRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/recovery/poison-decisions"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutTheAuditReadScopeFromPoisonDecisions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/recovery/poison-decisions")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_approval:decide"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAnAuthenticatedActorWithTheAuditReadScopeToReadPoisonDecisions() throws Exception {
        when(policyDecisionService.findPoisonDecisions()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/recovery/poison-decisions")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isOk());
    }
}
