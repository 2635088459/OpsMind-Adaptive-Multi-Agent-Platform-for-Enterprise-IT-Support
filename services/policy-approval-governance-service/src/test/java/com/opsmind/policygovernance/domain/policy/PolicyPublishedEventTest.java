package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-PG-035 (goal: "cross-domain contract tests with 02/03/04/05").
 * {@code policy.published.v1} (SPEC-PG-020) is the cache-invalidation
 * signal SPEC-PG-021 names by name — 05/03/04 each refresh their own local
 * policy cache on it — and never had its own shape test until this spec.
 */
@Tag("unit")
class PolicyPublishedEventTest {

    @Test
    void carriesTheRealEventTypeAndTheVersionsOwnAggregateIdentity() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        Instant effectiveFrom = Instant.now().minusSeconds(10);
        PolicyVersion published = PolicyVersion.draft("pv-1", "policy-1", 3, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", effectiveFrom, Instant.now());

        PolicyPublishedEvent event = PolicyPublishedEvent.from(published, "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("policy.published.v1");
        assertThat(event.aggregateType()).isEqualTo("PolicyVersion");
        assertThat(event.aggregateId()).isEqualTo("pv-1");
        assertThat(event.ticketId()).as("a policy publish is never ticket-scoped").isNull();
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.causationId()).isEqualTo("cause-1");
        assertThat(event.payload())
            .containsEntry("policyVersionId", "pv-1")
            .containsEntry("policyId", "policy-1")
            .containsEntry("versionNumber", 3)
            .containsEntry("publishedBy", "publisher-1")
            .containsEntry("effectiveFrom", effectiveFrom.toString());
    }

    /** SPEC-PG-021: a null {@code effectiveFrom} (published effective immediately) must not blow up the payload build. */
    @Test
    void toleratesAnAbsentEffectiveFrom() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion published = PolicyVersion.draft("pv-2", "policy-2", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", null, Instant.now());

        PolicyPublishedEvent event = PolicyPublishedEvent.from(published, "corr-1", null);

        assertThat(event.payload()).containsEntry("effectiveFrom", null);
    }
}
