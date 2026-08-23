package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyVersionTest {

    private PolicyVersion draft() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        return PolicyVersion.draft("pv-1", "policy-1", 1, List.of(rule), "author-1");
    }

    @Test
    void followsTheDeclaredStateMachine() {
        PolicyVersion reviewing = draft().transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now());
        assertThat(reviewing.status()).isEqualTo(PolicyStatus.REVIEWING);
        assertThat(reviewing.reviewedBy()).isEqualTo("reviewer-1");

        PolicyVersion published = reviewing.transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now());
        assertThat(published.status()).isEqualTo(PolicyStatus.PUBLISHED);
        assertThat(published.publishedBy()).isEqualTo("publisher-1");
        assertThat(published.publishedAt()).isNotNull();
    }

    @Test
    void rejectsSkippingReview() {
        assertThatThrownBy(() -> draft().transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now()))
            .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void rejectsTransitionsOutOfATerminalStatus() {
        PolicyVersion archived = draft()
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now())
            .transitionTo(PolicyStatus.DEPRECATED, "actor-1", null, Instant.now())
            .transitionTo(PolicyStatus.ARCHIVED, "actor-1", null, Instant.now());

        assertThatThrownBy(() -> archived.transitionTo(PolicyStatus.DRAFT, "actor-1", null, Instant.now()))
            .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    @Test
    void publishedVersionRulesAreImmutable() {
        PolicyVersion published = draft()
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now());

        assertThatThrownBy(() -> published.withRules(List.of()))
            .isInstanceOf(PolicyVersionImmutableException.class);
    }

    @Test
    void draftRulesCanStillBeReplaced() {
        PolicyVersion redrafted = draft().withRules(List.of());
        assertThat(redrafted.rules()).isEmpty();
    }
}
