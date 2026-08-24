package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.infrastructure.notification.NoOpApprovalNotificationAdapter;
import com.opsmind.policygovernance.support.FakeIdentityAuthorizationPort;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalDecisionRepository;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryProcessedEventRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-PG-025: the use-case layer behind 06's first inbound event consumer. */
@Tag("unit")
class ToolApprovalRequiredEventHandlerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApprovalRequestRepository requestRepository = new InMemoryApprovalRequestRepository();
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final InMemoryProcessedEventRepository processedEventRepository = new InMemoryProcessedEventRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
    );
    private final ApprovalService approvalService = new ApprovalService(
        requestRepository, new InMemoryApprovalDecisionRepository(), new FakeIdentityAuthorizationPort(),
        new NoOpApprovalNotificationAdapter(), auditService, new NoOpGovernanceMetrics(), clock
    );
    private final ToolApprovalRequiredEventHandler handler = new ToolApprovalRequiredEventHandler(
        new ConsumedEventDeduplicationService(processedEventRepository), approvalService
    );

    private RequestApprovalCommand command(String toolRequestId) {
        return new RequestApprovalCommand(
            toolRequestId, "hash-1", "tool-integration-gateway-service", toolRequestId, "ticket-1", null, toolRequestId,
            null, null, "tool-integration-gateway-service", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            null, "corr-1", "evt-1"
        );
    }

    @Test
    void aNewEventCreatesAnApprovalRequest() {
        handler.handle("evt-1", command("tool-req-1"));

        assertThat(requestRepository.findByRequestKey("tool-req-1")).isPresent();
    }

    /** 06-event-contracts §Idempotency: redelivering the same eventId must not create a second ApprovalRequest. */
    @Test
    void redeliveringTheSameEventIdDoesNotCreateASecondApprovalRequest() {
        handler.handle("evt-1", command("tool-req-1"));
        handler.handle("evt-1", command("tool-req-1"));

        assertThat(requestRepository.findByRequestKey("tool-req-1")).isPresent();
        // Only one ApprovalRequestedEvent should ever have been staged.
        assertThat(outboxEventRepository.all().stream().filter(r -> r.eventType().equals("approval.requested.v1")).count()).isEqualTo(1);
    }

    /** A genuinely different event (different eventId) for the same toolRequestId still lands on ApprovalService#request's own requestKey idempotency. */
    @Test
    void aDifferentEventForTheSameToolRequestReturnsTheExistingRequestRatherThanConflicting() {
        handler.handle("evt-1", command("tool-req-1"));
        handler.handle("evt-2", command("tool-req-1"));

        assertThat(requestRepository.findByRequestKey("tool-req-1")).isPresent();
    }
}
