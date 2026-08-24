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

/** SPEC-PG-026: mirrors {@code ToolApprovalRequiredEventHandlerTest}. */
@Tag("unit")
class WorkflowApprovalRequiredEventHandlerTest {

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
    private final WorkflowApprovalRequiredEventHandler handler = new WorkflowApprovalRequiredEventHandler(
        new ConsumedEventDeduplicationService(processedEventRepository), approvalService
    );

    private RequestApprovalCommand command(String workflowInstanceId) {
        return new RequestApprovalCommand(
            workflowInstanceId, "hash-1", "agent-runtime-service", workflowInstanceId, "ticket-1", workflowInstanceId,
            null, null, null, "agent-runtime-service", ApprovalType.WORKFLOW_ACTION, RiskLevel.HIGH, List.of(),
            null, "corr-1", "evt-1"
        );
    }

    @Test
    void aNewEventCreatesAnApprovalRequest() {
        handler.handle("evt-1", command("wf-1"));

        assertThat(requestRepository.findByRequestKey("wf-1")).isPresent();
    }

    /** 06-event-contracts §Idempotency: redelivering the same eventId must not create a second ApprovalRequest. */
    @Test
    void redeliveringTheSameEventIdDoesNotCreateASecondApprovalRequest() {
        handler.handle("evt-1", command("wf-1"));
        handler.handle("evt-1", command("wf-1"));

        assertThat(requestRepository.findByRequestKey("wf-1")).isPresent();
        assertThat(outboxEventRepository.all().stream().filter(r -> r.eventType().equals("approval.requested.v1")).count()).isEqualTo(1);
    }

    @Test
    void aDifferentEventForTheSameWorkflowInstanceReturnsTheExistingRequestRatherThanConflicting() {
        handler.handle("evt-1", command("wf-1"));
        handler.handle("evt-2", command("wf-1"));

        assertThat(requestRepository.findByRequestKey("wf-1")).isPresent();
    }
}
