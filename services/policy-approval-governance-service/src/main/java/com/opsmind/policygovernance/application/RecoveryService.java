package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;
import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SPEC-PG-033 (goal: "startup recovery workers", 10-failure-handling
 * §Recovery): runs the LLD's own ordered 5-step sequence in one call. Named
 * "on service startup" there, but — mirroring {@code
 * OutboxDispatchService#publishPending}'s and {@code
 * ApprovalExpiryService#expireDue}'s own established convention (never
 * wired to a {@code @Scheduled} trigger or an in-process boot hook
 * anywhere in this codebase, a repeatedly-confirmed platform-wide scope
 * boundary) — this is exposed as an admin-triggerable endpoint (deployment
 * scripts/external orchestration call it once at boot) rather than a new
 * {@code ApplicationRunner}/{@code ApplicationReadyEvent} pattern this
 * codebase has never used.
 *
 * <p>Steps 1/2 already existed as separate admin actions ({@code
 * OutboxDispatchService#publishPending}, SPEC-PG-003/024; {@code
 * ApprovalExpiryService#expireDue}, SPEC-PG-012) — this orchestrates them,
 * it does not reimplement them. Step 3 ("check policy version
 * consistency") and step 4 ("reschedule poison review") are this spec's own
 * new work — see {@link #checkPolicyVersionConsistency} and the poison
 * lookups in {@link #runRecovery} respectively; both are REVIEW/REPORT
 * surfaces, never automatic repairs (decisions and published versions are
 * immutable — 01-domain-model; individual poison outbox rows still require
 * a deliberate, accountable {@code OutboxAdminService#requeue} per row,
 * SPEC-PG-024's own design). Step 5 ("restore evaluator cache") is
 * deliberately NOT implemented: no evaluator cache exists anywhere in this
 * codebase — {@code RuleEvaluatorPort} is stateless and {@code
 * PolicyVersionRepository#findEffectiveVersion} is fetched fresh from
 * Postgres on every call (09-concurrency-and-idempotency §Policy Version
 * Race relies on exactly this) — so there is nothing to "restore," and
 * inventing a new caching layer this codebase has never had, for one LLD
 * word, would be scope creep, not closing a real gap.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final OutboxDispatchService outboxDispatchService;
    private final ApprovalExpiryService approvalExpiryService;
    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyDecisionRepository policyDecisionRepository;
    private final OutboxEventRepository outboxEventRepository;

    public RecoveryService(
        OutboxDispatchService outboxDispatchService,
        ApprovalExpiryService approvalExpiryService,
        PolicyRepository policyRepository,
        PolicyVersionRepository policyVersionRepository,
        PolicyDecisionRepository policyDecisionRepository,
        OutboxEventRepository outboxEventRepository
    ) {
        this.outboxDispatchService = outboxDispatchService;
        this.approvalExpiryService = approvalExpiryService;
        this.policyRepository = policyRepository;
        this.policyVersionRepository = policyVersionRepository;
        this.policyDecisionRepository = policyDecisionRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Deliberately not itself {@code @Transactional}: each step already
     * manages its own transaction boundary (steps 1/2 through their own
     * {@code @Transactional} services; steps 3/4 are plain reads) —
     * wrapping the whole orchestration in one outer transaction would mean
     * a failure in a later step could roll back an earlier step's already-committed
     * work, which is exactly wrong for independent recovery actions.
     * Mirrors {@code OutboxAdminService#dispatchPending}'s own precedent.
     */
    public RecoveryReport runRecovery() {
        OutboxDispatchService.DrainResult outboxDispatchResult = outboxDispatchService.publishPending();
        int expiredApprovalsCount = approvalExpiryService.expireDue().size();
        List<PolicyVersionConsistencyFinding> policyVersionConsistencyFindings = checkPolicyVersionConsistency();
        int poisonDecisionCount = policyDecisionRepository.findEvaluationFailed().size();
        List<String> deadLetteredOutboxIds = outboxEventRepository.findFailed().stream().map(OutboxEventRecord::outboxId).toList();

        log.atInfo()
            .addKeyValue("outboxPublished", outboxDispatchResult.published())
            .addKeyValue("outboxRetried", outboxDispatchResult.retried())
            .addKeyValue("outboxDeadLettered", outboxDispatchResult.deadLettered())
            .addKeyValue("expiredApprovalsCount", expiredApprovalsCount)
            .addKeyValue("policyVersionInconsistencyCount", policyVersionConsistencyFindings.size())
            .addKeyValue("poisonDecisionCount", poisonDecisionCount)
            .addKeyValue("deadLetteredOutboxCount", deadLetteredOutboxIds.size())
            .log("recovery run completed");

        return new RecoveryReport(
            outboxDispatchResult, expiredApprovalsCount, policyVersionConsistencyFindings,
            poisonDecisionCount, deadLetteredOutboxIds
        );
    }

    /**
     * "Check policy version consistency": {@code Policy#currentPublishedVersion}
     * is a plain version number, kept in sync with the real {@link
     * PolicyVersion} row only by {@code PolicyAdminService#publish}'s own
     * discipline (SPEC-PG-020) — this walk is the first thing that ever
     * verifies that discipline actually held, the same "write-time
     * invariant, never read-verified" gap {@code
     * GovernanceAuditService#verifyChain} closed for the hash chain
     * (SPEC-PG-031). A {@code null} pointer (never published) is not a
     * finding — only a pointer to a version that either does not exist or
     * is no longer {@code PUBLISHED} is.
     */
    private List<PolicyVersionConsistencyFinding> checkPolicyVersionConsistency() {
        List<PolicyVersionConsistencyFinding> findings = new ArrayList<>();
        for (Policy policy : policyRepository.findAll()) {
            Integer publishedVersionNumber = policy.currentPublishedVersion();
            if (publishedVersionNumber == null) {
                continue;
            }
            Optional<PolicyVersion> version = policyVersionRepository.findByPolicyIdAndVersionNumber(policy.policyId(), publishedVersionNumber);
            if (version.isEmpty()) {
                findings.add(new PolicyVersionConsistencyFinding(
                    policy.policyId(), publishedVersionNumber,
                    "currentPublishedVersion points to a policy_versions row that does not exist"
                ));
            } else if (version.get().status() != PolicyStatus.PUBLISHED) {
                findings.add(new PolicyVersionConsistencyFinding(
                    policy.policyId(), publishedVersionNumber,
                    "currentPublishedVersion points to version " + publishedVersionNumber + ", but its status is "
                        + version.get().status() + ", not PUBLISHED"
                ));
            }
        }
        return findings;
    }

    /** One policy header whose {@code currentPublishedVersion} pointer does not match a real, currently-{@code PUBLISHED} version. */
    public record PolicyVersionConsistencyFinding(String policyId, int versionNumber, String issue) {
    }

    /** The full recovery run's outcome — see {@link RecoveryService}'s own javadoc for what each field covers and why step 5 has no field. */
    public record RecoveryReport(
        OutboxDispatchService.DrainResult outboxDispatch,
        int expiredApprovalsCount,
        List<PolicyVersionConsistencyFinding> policyVersionConsistencyFindings,
        int poisonDecisionCount,
        List<String> deadLetteredOutboxIds
    ) {
    }
}
