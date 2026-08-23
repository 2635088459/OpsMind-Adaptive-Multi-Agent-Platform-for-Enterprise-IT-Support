package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.PolicyAdminService;
import com.opsmind.policygovernance.config.SecurityConfig;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-014 (11-security §Permission Model): "RBAC decides whether a user
 * can ... publish policy." Exercises the real {@code @PreAuthorize} gate
 * through the real Spring Security filter chain — unlike {@code
 * PolicyAdminService}'s own author==publisher check, this is the first test
 * in this service proving an actor without the required OAuth2 scope is
 * rejected before the request body is ever touched. Mirrors
 * ticket-workflow-service's {@code CreateTicketSecurityTest} pattern.
 */
@WebMvcTest(PolicyAdminController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
@Tag("security")
class PolicyAdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PolicyAdminService policyAdminService;

    @Test
    void rejectsAnUnauthenticatedPublishRequest() throws Exception {
        mockMvc.perform(post("/api/v1/policy-versions/pv-1:publish")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsAnAuthenticatedActorWithoutThePolicyPublishScope() throws Exception {
        mockMvc.perform(post("/api/v1/policy-versions/pv-1:publish")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "publisher-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:review")))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void allowsAnAuthenticatedActorWithThePolicyPublishScope() throws Exception {
        when(policyAdminService.publish(anyString(), anyString(), any(), anyString())).thenReturn(publishedVersionFixture());

        mockMvc.perform(post("/api/v1/policy-versions/pv-1:publish")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "publisher-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_policy:publish")))
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyVersionId").value("pv-1"));
    }

    private static PolicyVersion publishedVersionFixture() {
        return PolicyVersion.draft("pv-1", "policy-1", 1, List.of(), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now());
    }
}
