package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.ApprovalExpiryService;
import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-012: {@code POST /api/v1/approval-requests:expire-due} is the
 * approval expiry worker's invocation surface — see {@link
 * ApprovalExpiryController}'s own javadoc for why this endpoint exists
 * instead of a {@code @Scheduled} trigger.
 */
class ApprovalExpiryControllerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ApprovalRequestRepository requestRepository = new InMemoryApprovalRequestRepository();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GovernanceMetricsPort metrics = new NoOpGovernanceMetrics();
        var auditService = new com.opsmind.policygovernance.application.GovernanceAuditService(
            new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
            new com.opsmind.policygovernance.application.OutboxDispatchService(
                new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock
            ), clock
        );
        ApprovalExpiryService expiryService = new ApprovalExpiryService(requestRepository, auditService, metrics, clock);
        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalExpiryController(expiryService)).build();
    }

    @Test
    void expiresDueApprovalsAndReportsHowManyWereExpired() throws Exception {
        requestRepository.save(ApprovalRequest.requested(
            "ar-due", "rk-due", "hash-1", "tool-gateway", "src-req-due", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        ));
        requestRepository.save(ApprovalRequest.requested(
            "ar-open", "rk-open", "hash-1", "tool-gateway", "src-req-open", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().plusSeconds(3600), clock.instant()
        ));

        mockMvc.perform(post("/api/v1/approval-requests:expire-due"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiredCount").value(1))
            .andExpect(jsonPath("$.approvalRequestIds[0]").value("ar-due"));
    }
}
