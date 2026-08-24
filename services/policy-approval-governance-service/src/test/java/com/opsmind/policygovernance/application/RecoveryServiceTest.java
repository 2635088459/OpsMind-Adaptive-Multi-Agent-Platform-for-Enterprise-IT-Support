package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.DraftPolicyCommand;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyDecisionRepository;
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

/**
 * SPEC-PG-033 (goal: "startup recovery workers", "poison decision review").
 * See {@link RecoveryService}'s own javadoc for the scope this orchestrates
 * versus reimplements.
 */
@Tag("unit")
class RecoveryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final InMemoryApprovalRequestRepository approvalRequestRepository = new InMemoryApprovalRequestRepository();
    private final InMemoryPolicyRepository policyRepository = new InMemoryPolicyRepository();
    private final InMemoryPolicyVersionRepository policyVersionRepository = new InMemoryPolicyVersionRepository();
    private final InMemoryPolicyDecisionRepository policyDecisionRepository = new InMemoryPolicyDecisionRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
    );
    private final OutboxDispatchService outboxDispatchService =
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock);
    private final ApprovalExpiryService approvalExpiryService =
        new ApprovalExpiryService(approvalRequestRepository, auditService, new NoOpGovernanceMetrics(), clock);
    private final PolicyAdminService policyAdminService =
        new PolicyAdminService(policyRepository, policyVersionRepository, auditService, new NoOpGovernanceMetrics(), clock);
    private final RecoveryService service = new RecoveryService(
        outboxDispatchService, approvalExpiryService, policyRepository, policyVersionRepository,
        policyDecisionRepository, outboxEventRepository
    );

    /** "Replay pending outbox" (step 1) — reuses {@code OutboxDispatchService#publishPending}, does not reimplement it. */
    @Test
    void runRecoveryDispatchesPendingOutboxEvents() {
        outboxEventRepository.append(new OutboxEventRecord(
            "outbox-1", "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.PENDING, 0, null, null, clock.instant()
        ));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.outboxDispatch().published()).isEqualTo(1);
    }

    /** "Scan expired approvals" (step 2) — reuses {@code ApprovalExpiryService#expireDue}, does not reimplement it. */
    @Test
    void runRecoveryExpiresDueApprovalRequests() {
        ApprovalRequest expired = ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        approvalRequestRepository.save(expired);

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.expiredApprovalsCount()).isEqualTo(1);
    }

    /** "Check policy version consistency" (step 3): a cleanly published policy raises no finding. */
    @Test
    void policyVersionConsistencyFindsNothingForACleanlyPublishedPolicy() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion drafted = policyAdminService.draft(
            new DraftPolicyCommand("policy-1", "Policy 1", "global", List.of(rule), "author-1", "corr-1")
        );
        policyAdminService.review(drafted.policyVersionId(), "reviewer-1", "corr-1");
        policyAdminService.publish(drafted.policyVersionId(), "publisher-1", clock.instant(), "corr-1");

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.policyVersionConsistencyFindings()).isEmpty();
    }

    /** A policy that was drafted but never published has a {@code null} pointer — not itself a finding. */
    @Test
    void policyVersionConsistencyIgnoresAPolicyThatWasNeverPublished() {
        policyRepository.save(Policy.created("policy-never-published", "Never Published", "global", "author-1", clock.instant()));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.policyVersionConsistencyFindings()).isEmpty();
    }

    /** "Check policy version consistency": a pointer to a version number that does not exist at all is a finding. */
    @Test
    void policyVersionConsistencyDetectsAPointerToAMissingVersion() {
        policyRepository.save(new Policy(
            "policy-broken", "Broken Policy", "global", 7, com.opsmind.policygovernance.domain.policy.PolicyLifecycleStatus.ACTIVE,
            "author-1", clock.instant(), clock.instant()
        ));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.policyVersionConsistencyFindings()).hasSize(1);
        RecoveryService.PolicyVersionConsistencyFinding finding = report.policyVersionConsistencyFindings().get(0);
        assertThat(finding.policyId()).isEqualTo("policy-broken");
        assertThat(finding.versionNumber()).isEqualTo(7);
        assertThat(finding.issue()).contains("does not exist");
    }

    /** "Check policy version consistency": a pointer to a real version whose own status is no longer PUBLISHED is a finding. */
    @Test
    void policyVersionConsistencyDetectsAPointerToANonPublishedVersion() {
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion deprecated = PolicyVersion.draft("pv-1", "policy-stale", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, clock.instant())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", clock.instant().minusSeconds(20), clock.instant())
            .transitionTo(PolicyStatus.DEPRECATED, "publisher-1", null, clock.instant());
        policyVersionRepository.save(deprecated);
        policyRepository.save(new Policy(
            "policy-stale", "Stale Policy", "global", 1, com.opsmind.policygovernance.domain.policy.PolicyLifecycleStatus.ACTIVE,
            "author-1", clock.instant(), clock.instant()
        ));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.policyVersionConsistencyFindings()).hasSize(1);
        assertThat(report.policyVersionConsistencyFindings().get(0).issue()).contains("not PUBLISHED");
    }

    /** "Poison decision review" (step 4, decisions half): a review count only, never a repair — decisions are immutable. */
    @Test
    void runRecoveryReportsPoisonDecisionCount() {
        policyDecisionRepository.save(poisonDecision("pd-poison"));
        policyDecisionRepository.save(healthyDecision("pd-healthy"));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.poisonDecisionCount()).isEqualTo(1);
    }

    /** "Poison decision review" (step 4, outbox half): lists dead-lettered outbox ids for {@code OutboxAdminService#requeue} to act on individually. */
    @Test
    void runRecoveryReportsDeadLetteredOutboxIds() {
        outboxEventRepository.append(new OutboxEventRecord(
            "outbox-dead", "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.FAILED, 5, null, null, clock.instant()
        ));

        RecoveryService.RecoveryReport report = service.runRecovery();

        assertThat(report.deadLetteredOutboxIds()).containsExactly("outbox-dead");
    }

    private PolicyDecision poisonDecision(String policyDecisionId) {
        return new PolicyDecision(
            policyDecisionId, "dk-" + policyDecisionId, "hash-1", "user", "user-1", "READ", null, null, null,
            "tool-gateway", "src-req-1", null, null,
            DecisionEffect.DENY, RiskLevel.HIGH, false, true, List.of(), List.of(ReasonCode.EVALUATOR_UNAVAILABLE),
            "policy-1", "NONE", clock.instant(), null, true
        );
    }

    private PolicyDecision healthyDecision(String policyDecisionId) {
        return new PolicyDecision(
            policyDecisionId, "dk-" + policyDecisionId, "hash-1", "user", "user-1", "READ", null, null, null,
            "tool-gateway", "src-req-2", null, null,
            DecisionEffect.ALLOW, RiskLevel.LOW, false, false, List.of(), List.of(ReasonCode.POLICY_MATCHED),
            "policy-1", "1", clock.instant(), null, false
        );
    }
}
