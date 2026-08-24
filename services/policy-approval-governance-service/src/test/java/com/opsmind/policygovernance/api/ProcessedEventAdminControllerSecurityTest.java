package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.ProcessedEventAdminService;
import com.opsmind.policygovernance.application.exception.ProcessedEventNotFoundException;
import com.opsmind.policygovernance.config.SecurityConfig;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
 * replay/backfill"). Mirrors {@code RecoveryControllerSecurityTest}'s own
 * shape: the read endpoint follows {@code GovernanceAuditController}'s
 * audit-read-scope precedent, {@code :backfill} follows {@code
 * OutboxAdminController}'s baseline-authenticated-only precedent.
 */
@WebMvcTest(ProcessedEventAdminController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class ProcessedEventAdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProcessedEventAdminService processedEventAdminService;

    @Test
    void rejectsAnUnauthenticatedFindByEventIdRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/processed-events").param("eventId", "evt-1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutTheAuditReadScopeFromFindByEventId() throws Exception {
        mockMvc.perform(get("/api/v1/admin/processed-events")
                .param("eventId", "evt-1")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_approval:decide"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAnAuthenticatedActorWithTheAuditReadScopeToFindByEventId() throws Exception {
        when(processedEventAdminService.findByEventId("evt-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/processed-events")
                .param("eventId", "evt-1")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_governance:audit:read"))))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsAnUnauthenticatedBackfillRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/processed-events/evt-1/some-consumer:backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(backfillRequestBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAnyAuthenticatedActorToBackfill() throws Exception {
        mockMvc.perform(post("/api/v1/admin/processed-events/evt-1/some-consumer:backfill")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(backfillRequestBody())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1")).authorities(new SimpleGrantedAuthority("SCOPE_nothing:relevant"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("evt-1"))
            .andExpect(jsonPath("$.consumerName").value("some-consumer"))
            .andExpect(jsonPath("$.backfilled").value(true));
    }

    @Test
    void mapsProcessedEventNotFoundToNotFoundErrorEnvelope() throws Exception {
        doThrow(new ProcessedEventNotFoundException("evt-1", "some-consumer"))
            .when(processedEventAdminService).backfill(anyString(), anyString(), anyString(), anyString(), any());

        mockMvc.perform(post("/api/v1/admin/processed-events/evt-1/some-consumer:backfill")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(backfillRequestBody())
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROCESSED_EVENT_NOT_FOUND"));
    }

    @Test
    void rejectsABackfillRequestMissingTheReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/processed-events/evt-1/some-consumer:backfill")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "actor-1"))))
            .andExpect(status().isBadRequest());
    }

    private static String backfillRequestBody() {
        return """
            {
              "reason": "reprocessing after bugfix"
            }
            """;
    }
}
