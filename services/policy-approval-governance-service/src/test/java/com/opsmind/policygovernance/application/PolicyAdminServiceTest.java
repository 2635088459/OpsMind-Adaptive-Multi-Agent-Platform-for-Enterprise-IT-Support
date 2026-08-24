package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.DraftPolicyCommand;
import com.opsmind.policygovernance.application.exception.PolicyPublishSeparationOfDutiesException;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.IllegalPolicyTransitionException;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.domain.policy.PolicyPublishedEvent;
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
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
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

    /**
     * SPEC-PG-018 (goal: "reviewer/publisher separation of duties"): the
     * same guard {@link #theAuthorCannotPublishTheirOwnPolicyVersion} proves
     * for the author now also applies to the reviewer — a review step the
     * reviewer can also publish is not a real second pair of eyes.
     */
    @Test
    void theReviewerCannotPublishTheVersionTheyReviewed() {
        PolicyVersion drafted = service.draft(draftCommand("policy-4"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");

        assertThatThrownBy(() -> service.publish(drafted.policyVersionId(), "reviewer-1", Instant.now(), "corr-1"))
            .isInstanceOf(PolicyPublishSeparationOfDutiesException.class);
    }

    /**
     * SPEC-PG-019 (goal: "rule fixes require new versions"): drafting again
     * for a policyId that already has a version — regardless of that
     * version's own status — must produce the next version number, never
     * reuse one (INV-PG-006, {@code uq_policy_versions_policy_version}).
     */
    @Test
    void draftingAgainAfterAPublishedVersionIncrementsTheVersionNumber() {
        PolicyVersion firstVersion = service.draft(draftCommand("policy-5"));
        service.review(firstVersion.policyVersionId(), "reviewer-1", "corr-1");
        service.publish(firstVersion.policyVersionId(), "publisher-1", Instant.now(), "corr-1");

        PolicyVersion secondVersion = service.draft(draftCommand("policy-5"));

        assertThat(firstVersion.versionNumber()).isEqualTo(1);
        assertThat(secondVersion.versionNumber()).isEqualTo(2);
        assertThat(secondVersion.status()).isEqualTo(PolicyStatus.DRAFT);
        // The published version's own rules stay untouched — this is a new, independent snapshot.
        assertThat(versionRepository.findById(firstVersion.policyVersionId()).orElseThrow().status()).isEqualTo(PolicyStatus.PUBLISHED);
    }

    /** A brand new policyId always starts at version 1, whether or not other policies already have higher-numbered versions. */
    @Test
    void aBrandNewPolicyIdStartsAtVersionOne() {
        service.draft(draftCommand("policy-6"));
        service.draft(draftCommand("policy-6"));

        PolicyVersion unrelatedPolicy = service.draft(draftCommand("policy-7"));

        assertThat(unrelatedPolicy.versionNumber()).isEqualTo(1);
    }

    /** SPEC-PG-020 (goal: "archive... states"): the terminal DEPRECATED -> ARCHIVED transition. */
    @Test
    void archiveTransitionsADeprecatedVersionToArchived() {
        PolicyVersion drafted = service.draft(draftCommand("policy-8"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");
        service.publish(drafted.policyVersionId(), "publisher-1", Instant.now(), "corr-1");
        service.deprecate(drafted.policyVersionId(), "actor-1", "corr-1");

        PolicyVersion archived = service.archive(drafted.policyVersionId(), "actor-1", "corr-1");

        assertThat(archived.status()).isEqualTo(PolicyStatus.ARCHIVED);
    }

    @Test
    void archivingAVersionThatWasNeverDeprecatedIsRejected() {
        PolicyVersion drafted = service.draft(draftCommand("policy-9"));

        assertThatThrownBy(() -> service.archive(drafted.policyVersionId(), "actor-1", "corr-1"))
            .isInstanceOf(IllegalPolicyTransitionException.class);
    }

    /**
     * SPEC-PG-020 (goal: "supersede... states"): a policy can only ever have
     * one effective PUBLISHED version — publishing version 2 must
     * automatically move the still-PUBLISHED version 1 to SUPERSEDED, in the
     * same publish() call, not as a separate manual step.
     */
    @Test
    void publishingANewVersionSupersedesThePreviouslyPublishedVersion() {
        PolicyVersion v1 = service.draft(draftCommand("policy-10"));
        service.review(v1.policyVersionId(), "reviewer-1", "corr-1");
        service.publish(v1.policyVersionId(), "publisher-1", Instant.now(), "corr-1");

        PolicyVersion v2 = service.draft(draftCommand("policy-10"));
        service.review(v2.policyVersionId(), "reviewer-2", "corr-1");
        PolicyVersion publishedV2 = service.publish(v2.policyVersionId(), "publisher-2", Instant.now(), "corr-1");

        assertThat(publishedV2.status()).isEqualTo(PolicyStatus.PUBLISHED);
        assertThat(versionRepository.findById(v1.policyVersionId()).orElseThrow().status()).isEqualTo(PolicyStatus.SUPERSEDED);
        assertThat(policyRepository.findById("policy-10").orElseThrow().currentPublishedVersion()).isEqualTo(2);
    }

    /** The very first publish for a policy has no predecessor to supersede — must not throw or no-op incorrectly. */
    @Test
    void theFirstPublishForAPolicyHasNothingToSupersede() {
        PolicyVersion drafted = service.draft(draftCommand("policy-11"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");

        PolicyVersion published = service.publish(drafted.policyVersionId(), "publisher-1", Instant.now(), "corr-1");

        assertThat(published.status()).isEqualTo(PolicyStatus.PUBLISHED);
    }

    /**
     * SPEC-PG-020 (goal: "policy.published/changed events"): {@code
     * publish()} must stage the real {@code policy.published.v1} event, not
     * the generic {@code governance.audit.policy_published.v1} placeholder
     * — mirroring how SPEC-PG-010 graduated {@code approval.requested.v1}.
     */
    @Test
    void publishStagesTheRealPolicyPublishedEventWithCorrectAggregateIdentity() {
        PolicyVersion drafted = service.draft(draftCommand("policy-12"));
        service.review(drafted.policyVersionId(), "reviewer-1", "corr-1");

        PolicyVersion published = service.publish(drafted.policyVersionId(), "publisher-1", Instant.now(), "corr-1");

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(PolicyPublishedEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no policy.published.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("PolicyVersion");
        assertThat(staged.aggregateId()).isEqualTo(published.policyVersionId());
        assertThat(staged.payloadJson()).contains("\"policyId\":\"policy-12\"");
    }

    /** A conflicting supersede attempt (e.g. the old version was already manually deprecated) is skipped, not an error. */
    @Test
    void aPreviousVersionAlreadyDeprecatedOutOfBandIsNotForciblySuperseded() {
        PolicyVersion v1 = service.draft(draftCommand("policy-13"));
        service.review(v1.policyVersionId(), "reviewer-1", "corr-1");
        service.publish(v1.policyVersionId(), "publisher-1", Instant.now(), "corr-1");
        service.deprecate(v1.policyVersionId(), "actor-1", "corr-1");

        PolicyVersion v2 = service.draft(draftCommand("policy-13"));
        service.review(v2.policyVersionId(), "reviewer-2", "corr-1");
        service.publish(v2.policyVersionId(), "publisher-2", Instant.now(), "corr-1");

        assertThat(versionRepository.findById(v1.policyVersionId()).orElseThrow().status())
            .as("already DEPRECATED, not PUBLISHED — publish() must not try to transition it to SUPERSEDED")
            .isEqualTo(PolicyStatus.DEPRECATED);
    }
}
