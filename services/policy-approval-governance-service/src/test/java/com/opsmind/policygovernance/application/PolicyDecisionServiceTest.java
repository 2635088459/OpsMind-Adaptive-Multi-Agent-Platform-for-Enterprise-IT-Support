package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.application.exception.DecisionKeyConflictException;
import com.opsmind.policygovernance.application.exception.PolicyDecisionNotFoundException;
import com.opsmind.policygovernance.application.port.RuleEvaluatorPort;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.infrastructure.evaluator.DefaultRuleEvaluatorAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyDecisionRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyVersionRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyDecisionServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryPolicyVersionRepository versionRepository = new InMemoryPolicyVersionRepository();
    private final InMemoryPolicyDecisionRepository decisionRepository = new InMemoryPolicyDecisionRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(new InMemoryOutboxEventRepository(), new FakeMessageBrokerPublisher(), clock), clock
    );
    private final PolicyDecisionService service = new PolicyDecisionService(
        versionRepository, decisionRepository, new DefaultRuleEvaluatorAdapter(), auditService, new NoOpGovernanceMetrics(), clock
    );

    private EvaluateDecisionCommand command(String decisionKey) {
        return command(decisionKey, "hash-1");
    }

    private EvaluateDecisionCommand command(String decisionKey, String inputHash) {
        return new EvaluateDecisionCommand(
            decisionKey, inputHash, "user", "user-1", "READ", "ticket", "ticket-1", "tenant-1",
            "tool-gateway", "src-req-1", "ticket-1", null, "policy-1", "corr-1", null
        );
    }

    @Test
    void deniesFailSafeWhenNoEffectivePolicyVersionExists() {
        PolicyDecision decision = service.evaluate(command("dk-1"));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(decision.reasonCodes()).contains(ReasonCode.POLICY_VERSION_NOT_FOUND);
        assertThat(decision.evaluationFailed())
            .as("03-state-machine: no effective version is EVALUATION_FAILED, distinguishable from a rule-driven DENY")
            .isTrue();
    }

    @Test
    void isIdempotentOnDecisionKeyWithMatchingInputHash() {
        PolicyDecision first = service.evaluate(command("dk-2"));
        PolicyDecision second = service.evaluate(command("dk-2"));

        assertThat(second.policyDecisionId()).isEqualTo(first.policyDecisionId());
    }

    @Test
    void reusingADecisionKeyWithADifferentInputHashIsAConflict() {
        service.evaluate(command("dk-conflict", "hash-1"));

        assertThatThrownBy(() -> service.evaluate(command("dk-conflict", "hash-2")))
            .isInstanceOf(DecisionKeyConflictException.class);
    }

    @Test
    void evaluatesAgainstTheEffectivePublishedVersion() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion published = PolicyVersion.draft("pv-1", "policy-1", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, clock.instant())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", clock.instant().minusSeconds(10), clock.instant());
        versionRepository.save(published);

        PolicyDecision decision = service.evaluate(command("dk-3"));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
        assertThat(decision.policyVersion()).isEqualTo("1");
        assertThat(decision.evaluationFailed()).isFalse();
    }

    @Test
    void failClosesAndPersistsDecisionWhenEvaluatorThrows() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion published = PolicyVersion.draft("pv-1", "policy-1", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, clock.instant())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", clock.instant().minusSeconds(10), clock.instant());
        versionRepository.save(published);

        PolicyDecisionService failingService = new PolicyDecisionService(
            versionRepository, decisionRepository, new ThrowingRuleEvaluator(), auditService, new NoOpGovernanceMetrics(), clock
        );

        PolicyDecision decision = failingService.evaluate(command("dk-evaluator-failure"));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(decision.evaluationFailed()).isTrue();
        assertThat(decision.reasonCodes()).containsExactly(ReasonCode.EVALUATOR_UNAVAILABLE);
        assertThat(decisionRepository.findByDecisionKey("dk-evaluator-failure")).contains(decision);
    }

    @Test
    void findByIdReturnsThePersistedDecision() {
        PolicyDecision evaluated = service.evaluate(command("dk-find-1"));

        PolicyDecision found = service.findById(evaluated.policyDecisionId());

        assertThat(found).isEqualTo(evaluated);
    }

    @Test
    void findByIdThrowsWhenNoSuchDecisionExists() {
        assertThatThrownBy(() -> service.findById("does-not-exist"))
            .isInstanceOf(PolicyDecisionNotFoundException.class);
    }

    /**
     * SPEC-PG-008 / 09-concurrency-and-idempotency §Policy Version Race:
     * "the effective version is selected once, in this method's own
     * transaction ... a concurrently committed new version cannot
     * retroactively change a decision already in flight." The evaluator
     * itself commits a newer PUBLISHED version mid-evaluation, simulating a
     * concurrent {@code PolicyAdminService#publish}; the in-flight decision
     * must still bind to the version it was handed, not whatever is now
     * latest in the repository.
     */
    @Test
    void bindsTheEffectiveVersionChosenAtEvaluationStartDespiteAConcurrentPublish() {
        PolicyRule ruleV1 = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion v1 = PolicyVersion.draft("pv-1", "policy-1", 1, List.of(ruleV1), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, clock.instant())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", clock.instant().minusSeconds(10), clock.instant());
        versionRepository.save(v1);

        RuleEvaluatorPort raceSimulatingEvaluator = (effectiveVersion, input) -> {
            PolicyRule ruleV2 = new PolicyRule("rule-2", List.of(), DecisionEffect.DENY, RiskLevel.HIGH, false, List.of());
            PolicyVersion v2 = PolicyVersion.draft("pv-2", "policy-1", 2, List.of(ruleV2), "author-1")
                .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, clock.instant())
                .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", clock.instant().minusSeconds(5), clock.instant());
            versionRepository.save(v2);

            PolicyRule matchedRule = effectiveVersion.rules().get(0);
            return new RuleEvaluatorPort.Result(
                matchedRule.effect(), matchedRule.riskLevel(), false, List.of(), List.of(ReasonCode.POLICY_MATCHED)
            );
        };
        PolicyDecisionService raceService = new PolicyDecisionService(
            versionRepository, decisionRepository, raceSimulatingEvaluator, auditService, new NoOpGovernanceMetrics(), clock
        );

        PolicyDecision decision = raceService.evaluate(command("dk-race"));

        assertThat(decision.policyVersion())
            .as("the decision started against v1 must not be retroactively re-pointed at the concurrently published v2")
            .isEqualTo("1");
        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);

        // A fresh evaluation afterward correctly observes the newly published v2 —
        // proving v2 really was committed and visible, not merely unreachable.
        PolicyDecision afterPublish = raceService.evaluate(command("dk-race-after-publish"));
        assertThat(afterPublish.policyVersion()).isEqualTo("2");
        assertThat(afterPublish.effect()).isEqualTo(DecisionEffect.DENY);
    }

    private static final class ThrowingRuleEvaluator implements RuleEvaluatorPort {
        @Override
        public Result evaluate(PolicyVersion effectiveVersion, Input input) {
            throw new IllegalStateException("synthetic evaluator outage");
        }
    }
}
