package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.GovernanceAuditService;
import com.opsmind.policygovernance.config.SecurityConfig;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-031 (goal: "compliance reports", "audit retention"). Mirrors
 * {@link GovernanceAuditControllerSecurityTest}'s own pattern for {@code
 * /compliance-report} (same {@code SCOPE_governance:audit:read} as reading
 * any other audit view), and {@link OutboxAdminController}'s own precedent
 * for {@code :archive} — no dedicated scope, baseline authenticated actor
 * only (see {@link GovernanceAuditComplianceController}'s own javadoc).
 */
@WebMvcTest(GovernanceAuditComplianceController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class GovernanceAuditComplianceControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GovernanceAuditService governanceAuditService;

    @Test
    void rejectsAnUnauthenticatedComplianceReportRequest() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit/compliance-report"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutTheAuditReadScopeFromTheComplianceReport() throws Exception {
        mockMvc.perform(get("/api/v1/governance-audit/compliance-report")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_approval:decide"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAnAuthenticatedActorWithTheAuditReadScopeToReadTheComplianceReport() throws Exception {
        when(governanceAuditService.complianceReport()).thenReturn(new GovernanceAuditService.ComplianceReport(
            2, 1, 1, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
            Map.of(GovernanceAuditRecord.Action.APPROVAL_REQUESTED, 2L),
            new GovernanceAuditService.ChainVerificationResult(2, true, null)
        ));

        mockMvc.perform(get("/api/v1/governance-audit/compliance-report")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRecords").value(2))
            .andExpect(jsonPath("$.activeRecords").value(1))
            .andExpect(jsonPath("$.archivedRecords").value(1))
            .andExpect(jsonPath("$.chainIntact").value(true))
            .andExpect(jsonPath("$.countsByAction.APPROVAL_REQUESTED").value(2));
    }

    /** Unlike the compliance report, {@code :archive} needs no specific scope — only a baseline authenticated actor, mirroring {@code OutboxAdminController}'s own precedent. */
    @Test
    void rejectsAnUnauthenticatedArchiveRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/governance-audit:archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(archiveRequestBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAnyAuthenticatedActorToArchive() throws Exception {
        when(governanceAuditService.archiveRecordedBefore(90, "actor-1", "quarterly retention sweep", "corr-1")).thenReturn(5);

        mockMvc.perform(post("/api/v1/admin/governance-audit:archive")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(archiveRequestBody())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_nothing:relevant"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedCount").value(5));
    }

    @Test
    void rejectsAnArchiveRequestMissingTheReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/governance-audit:archive")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"retentionDays\": 90}")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))))
            .andExpect(status().isBadRequest());
    }

    private static String archiveRequestBody() {
        return """
            {
              "retentionDays": 90,
              "reason": "quarterly retention sweep"
            }
            """;
    }
}
