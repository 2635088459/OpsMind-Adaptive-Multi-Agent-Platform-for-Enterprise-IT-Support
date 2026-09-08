package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.PolicyQueryService;
import com.opsmind.policygovernance.application.exception.PolicyNotFoundException;
import com.opsmind.policygovernance.config.SecurityConfig;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The read side of the Admin Policy API is gated by the new {@code policy:read} client scope. */
@WebMvcTest(PolicyQueryController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class PolicyQueryControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PolicyQueryService policyQueryService;

    @Test
    void rejectsAnUnauthenticatedListRequest() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutThePolicyReadScope() throws Exception {
        mockMvc.perform(get("/api/v1/policies")
                .with(jwt().jwt(j -> j.claim("sub", "agent-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:draft"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void listsPoliciesForAnActorWithThePolicyReadScope() throws Exception {
        when(policyQueryService.listPolicies()).thenReturn(List.of(
            Policy.created("p-1", "Alpha", "global", "author-1", Instant.parse("2026-01-01T00:00:00Z"))
        ));

        mockMvc.perform(get("/api/v1/policies")
                .with(jwt().jwt(j -> j.claim("sub", "agent-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].policyId").value("p-1"))
            .andExpect(jsonPath("$[0].policyName").value("Alpha"));
    }

    @Test
    void returnsAPolicyWithItsVersionHistory() throws Exception {
        when(policyQueryService.getPolicy("p-1")).thenReturn(
            Policy.created("p-1", "Alpha", "global", "author-1", Instant.parse("2026-01-01T00:00:00Z"))
        );
        when(policyQueryService.getPolicyVersions("p-1")).thenReturn(List.of(
            PolicyVersion.draft("pv-1", "p-1", 1, List.of(), "author-1")
        ));

        mockMvc.perform(get("/api/v1/policies/p-1")
                .with(jwt().jwt(j -> j.claim("sub", "agent-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policy.policyId").value("p-1"))
            .andExpect(jsonPath("$.versions[0].policyVersionId").value("pv-1"));
    }

    @Test
    void returns404ForAnUnknownPolicy() throws Exception {
        when(policyQueryService.getPolicy(anyString())).thenThrow(new PolicyNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/policies/missing")
                .with(jwt().jwt(j -> j.claim("sub", "agent-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"));
    }
}
