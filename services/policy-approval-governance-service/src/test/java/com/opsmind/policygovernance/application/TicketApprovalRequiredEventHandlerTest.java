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

/** SPEC-PG-027: mirrors {@code ToolApprovalRequiredEventHandlerTest}. */
@Tag("unit")
class TicketApprovalRequiredEventHandlerTest {

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
    private final TicketApprovalRequiredEventHandler handler = new TicketApprovalRequiredEventHandler(
        new ConsumedEventDeduplicationService(processedEventRepository), approvalService
    );

    private RequestApprovalCommand command(String ticketId) {
        return new RequestApprovalCommand(
            ticketId, "hash-1", "ticket-workflow-service", ticketId, ticketId, null,
            null, null, null, "ticket-workflow-service", ApprovalType.TICKET_SLA_EXCEPTION, RiskLevel.HIGH, List.of(),
            null, "corr-1", "evt-1"
        );
    }

    @Test
    void aNewEventCreatesAnApprovalRequest() {
        handler.handle("evt-1", command("ticket-1"));

        assertThat(requestRepository.findByRequestKey("ticket-1")).isPresent();
    }

    /** 06-event-contracts §Idempotency: redelivering the same eventId must not create a second ApprovalRequest. */
    @Test
    void redeliveringTheSameEventIdDoesNotCreateASecondApprovalRequest() {
        handler.handle("evt-1", command("ticket-1"));
        handler.handle("evt-1", command("ticket-1"));

        assertThat(requestRepository.findByRequestKey("ticket-1")).isPresent();
        assertThat(outboxEventRepository.all().stream().filter(r -> r.eventType().equals("approval.requested.v1")).count()).isEqualTo(1);
    }

    @Test
    void aDifferentEventForTheSameTicketReturnsTheExistingRequestRatherThanConflicting() {
        handler.handle("evt-1", command("ticket-1"));
        handler.handle("evt-2", command("ticket-1"));

        assertThat(requestRepository.findByRequestKey("ticket-1")).isPresent();
    }
}
