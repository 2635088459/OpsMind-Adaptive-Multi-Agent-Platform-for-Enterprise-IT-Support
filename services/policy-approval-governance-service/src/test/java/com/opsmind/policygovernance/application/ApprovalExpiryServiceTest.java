package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ApprovalExpiryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApprovalRequestRepository requestRepository = new InMemoryApprovalRequestRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock), clock
    );
    private final ApprovalExpiryService service = new ApprovalExpiryService(requestRepository, auditService, new NoOpGovernanceMetrics(), clock);

    @Test
    void expiresOnlyRequestedApprovalsPastTheirExpiry() {
        ApprovalRequest expired = ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        ApprovalRequest stillOpen = ApprovalRequest.requested(
            "ar-2", "rk-2", "hash-2", "tool-gateway", "src-req-2", null, null, "tool-req-2", null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().plusSeconds(3600), clock.instant()
        );
        requestRepository.save(expired);
        requestRepository.save(stillOpen);

        List<ApprovalRequest> result = service.expireDue();

        assertThat(result).extracting(ApprovalRequest::approvalRequestId).containsExactly("ar-1");
        assertThat(requestRepository.findById("ar-1").orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(requestRepository.findById("ar-2").orElseThrow().status()).isEqualTo(ApprovalStatus.REQUESTED);
    }
}
