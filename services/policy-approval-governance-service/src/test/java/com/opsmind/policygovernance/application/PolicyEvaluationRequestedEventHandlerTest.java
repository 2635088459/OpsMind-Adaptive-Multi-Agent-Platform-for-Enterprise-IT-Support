package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.infrastructure.evaluator.DefaultRuleEvaluatorAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyDecisionRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyVersionRepository;
import com.opsmind.policygovernance.support.InMemoryProcessedEventRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-PG-028: mirrors {@code ToolApprovalRequiredEventHandlerTest}, targeting PolicyDecisionService instead of ApprovalService. */
@Tag("unit")
class PolicyEvaluationRequestedEventHandlerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryPolicyDecisionRepository decisionRepository = new InMemoryPolicyDecisionRepository();
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final InMemoryProcessedEventRepository processedEventRepository = new InMemoryProcessedEventRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
    );
    private final PolicyDecisionService policyDecisionService = new PolicyDecisionService(
        new InMemoryPolicyVersionRepository(), decisionRepository, new DefaultRuleEvaluatorAdapter(),
        auditService, new NoOpGovernanceMetrics(), clock
    );
    private final PolicyEvaluationRequestedEventHandler handler = new PolicyEvaluationRequestedEventHandler(
        new ConsumedEventDeduplicationService(processedEventRepository), policyDecisionService
    );

    private EvaluateDecisionCommand command(String decisionKey) {
        return new EvaluateDecisionCommand(
            decisionKey, "hash-1", "user", "user-1", "READ", false, "memory-record", "mem-1", "tenant-1",
            "memory-knowledge-service", "src-req-1", "ticket-1", null, "policy-1", "corr-1", "evt-1"
        );
    }

    @Test
    void aNewEventCreatesAPolicyDecision() {
        handler.handle("evt-1", command("dk-1"));

        assertThat(decisionRepository.findByDecisionKey("dk-1")).isPresent();
    }

    /** 06-event-contracts §Idempotency: redelivering the same eventId must not create a second PolicyDecision. */
    @Test
    void redeliveringTheSameEventIdDoesNotCreateASecondDecision() {
        handler.handle("evt-1", command("dk-1"));
        handler.handle("evt-1", command("dk-1"));

        assertThat(outboxEventRepository.all().stream().filter(r -> r.eventType().equals("policy.decision.created.v1")).count()).isEqualTo(1);
    }

    @Test
    void aDifferentEventForTheSameDecisionKeyReturnsTheExistingDecisionRatherThanConflicting() {
        handler.handle("evt-1", command("dk-1"));
        handler.handle("evt-2", command("dk-1"));

        assertThat(decisionRepository.findByDecisionKey("dk-1")).isPresent();
    }
}
