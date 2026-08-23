package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.DraftPolicyCommand;
import com.opsmind.policygovernance.application.exception.PolicyPublishSeparationOfDutiesException;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.IllegalPolicyTransitionException;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyVersionRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyAdminServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryPolicyRepository policyRepository = new InMemoryPolicyRepository();
    private final InMemoryPolicyVersionRepository versionRepository = new InMemoryPolicyVersionRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock), clock
    );
    private final PolicyAdminService service = new PolicyAdminService(policyRepository, versionRepository, auditService, new NoOpGovernanceMetrics(), clock);

    private DraftPolicyCommand draftCommand(String policyId) {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        return new DraftPolicyCommand(policyId, "Policy " + policyId, "global", List.of(rule), "author-1", "corr-1");
    }

    @Test
    void draftReviewPublishByADifferentActorSucceeds() {
        PolicyVersion drafted = service.draft(draftCommand("policy-1"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");

        PolicyVersion published = service.publish(drafted.policyVersionId(), "publisher-1", Instant.now(), "corr-1");

        assertThat(published.status()).isEqualTo(PolicyStatus.PUBLISHED);
        assertThat(policyRepository.findById("policy-1").orElseThrow().currentPublishedVersion()).isEqualTo(1);
    }

    @Test
    void theAuthorCannotPublishTheirOwnPolicyVersion() {
        PolicyVersion drafted = service.draft(draftCommand("policy-2"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");

        assertThatThrownBy(() -> service.publish(drafted.policyVersionId(), "author-1", Instant.now(), "corr-1"))
            .isInstanceOf(PolicyPublishSeparationOfDutiesException.class);
    }

    @Test
    void publishingWithoutReviewIsRejected() {
        PolicyVersion drafted = service.draft(draftCommand("policy-3"));

        assertThatThrownBy(() -> service.publish(drafted.policyVersionId(), "publisher-1", Instant.now(), "corr-1"))
            .isInstanceOf(IllegalPolicyTransitionException.class);
    }
}
