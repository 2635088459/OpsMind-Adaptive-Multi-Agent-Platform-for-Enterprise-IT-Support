package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.DraftPolicyCommand;
import com.opsmind.policygovernance.application.exception.PolicyNotFoundException;
import com.opsmind.policygovernance.application.exception.PolicyPublishSeparationOfDutiesException;
import com.opsmind.policygovernance.application.exception.PolicyVersionNotFoundException;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyPublishedEvent;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * UC-PG-005 (Policy Publication): draft -> review -> publish -> deprecate.
 * {@link #publish} enforces the test-plan security rule "policy author
 * cannot publish their own unreviewed policy", extended by SPEC-PG-018
 * (goal: "reviewer/publisher separation of duties") to also refuse the
 * version's own reviewer — a review step the reviewer can also publish is
 * not a real second pair of eyes. RBAC (which OAuth2 scope each action
 * requires) is enforced one layer up, in {@code api.PolicyAdminController}'s
 * own {@code @PreAuthorize} annotations (SPEC-PG-014/018), not here.
 *
 * <p>SPEC-PG-029 (12-observability §Logs): every action here now logs a
 * structured event carrying {@code correlationId}/{@code policyVersion} —
 * this service had zero structured logging before, unlike every other
 * governance-mutating application service in this codebase.
 * {@code causationId} is deliberately not threaded through (unlike
 * approval commands): nothing in 06-event-contracts names an inbound event
 * these actions react to — draft/review/publish/deprecate/archive are pure
 * HTTP-actor-initiated admin actions with no "cause" event to carry.
 */
@Service
public class PolicyAdminService {

    private static final Logger log = LoggerFactory.getLogger(PolicyAdminService.class);

    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final GovernanceAuditService auditService;
    private final GovernanceMetricsPort metrics;
    private final Clock clock;

    public PolicyAdminService(
        PolicyRepository policyRepository,
        PolicyVersionRepository policyVersionRepository,
        GovernanceAuditService auditService,
        GovernanceMetricsPort metrics,
        Clock clock
    ) {
        this.policyRepository = policyRepository;
        this.policyVersionRepository = policyVersionRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * SPEC-PG-019 (goal: "rule fixes require new versions"): the version
     * number is one past whatever the highest version this policy has ever
     * had is — {@code PolicyVersion} enforces immutability once a version is
     * {@code PUBLISHED} (see its own {@code withRules} javadoc for
     * INV-PG-006), so the only way to change a published policy's rules is a
     * brand new version, never higher than {@link
     * PolicyVersionRepository#findLatestVersion} regardless of that latest
     * version's own status (DRAFT/REVIEWING/PUBLISHED/etc. all count — two
     * versions can never share a number, {@code uq_policy_versions_policy_version}
     * enforces this at the database level too).
     */
    @Transactional
    public PolicyVersion draft(DraftPolicyCommand command) {
        Policy policy = policyRepository.findById(command.policyId())
            .orElseGet(() -> policyRepository.save(
                Policy.created(command.policyId(), command.policyName(), command.scope(), command.createdBy(), clock.instant())
            ));

        int nextVersionNumber = policyVersionRepository.findLatestVersion(policy.policyId())
            .map(v -> v.versionNumber() + 1)
            .orElse(1);
        PolicyVersion draft = PolicyVersion.draft(
            UUID.randomUUID().toString(), policy.policyId(), nextVersionNumber, command.rules(), command.createdBy()
        );
        PolicyVersion saved = policyVersionRepository.save(draft);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_DRAFTED, command.createdBy(), "06", policy.policyId(),
            policy.policyId(), String.valueOf(saved.versionNumber()), "policy version drafted", command.correlationId(), null,
            null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("policyVersion", saved.versionNumber())
            .log("policy version drafted");
        return saved;
    }

    @Transactional
    public PolicyVersion review(String policyVersionId, String reviewerId, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        PolicyVersion reviewed = version.transitionTo(PolicyStatus.REVIEWING, reviewerId, null, clock.instant());
        PolicyVersion saved = policyVersionRepository.save(reviewed);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_REVIEWED, reviewerId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version under review", correlationId, null,
            null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("policyVersion", saved.versionNumber())
            .log("policy version under review");
        return saved;
    }

    /**
     * SPEC-PG-020 (goal: "policy.published/changed events"): stages the
     * real, versioned {@code policy.published.v1} event ({@link
     * PolicyPublishedEvent}) instead of the generic outbox placeholder —
     * 05/03/04 each refresh their own local policy cache on this event
     * (SPEC-PG-021's own "policy cache refresh events").
     *
     * <p>Also (goal: "supersede... states"): a policy can only ever have one
     * effective {@code PUBLISHED} version at a time — if this policy already
     * had one (tracked on {@code Policy#currentPublishedVersion}), publishing
     * a new one automatically transitions the old one to {@code SUPERSEDED}
     * (03-state-machine's {@code PUBLISHED -> SUPERSEDED}), in the same
     * transaction as the new publish. Looked up defensively (only if still
     * {@code PUBLISHED}): an operator could have already moved it to {@code
     * DEPRECATED} out of band, and {@code DEPRECATED} has no {@code
     * SUPERSEDED} transition to take.
     */
    @Transactional
    public PolicyVersion publish(String policyVersionId, String publisherId, Instant effectiveFrom, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        if (version.createdBy().equals(publisherId)) {
            throw new PolicyPublishSeparationOfDutiesException(policyVersionId, "author");
        }
        if (publisherId.equals(version.reviewedBy())) {
            throw new PolicyPublishSeparationOfDutiesException(policyVersionId, "reviewer");
        }
        Instant now = clock.instant();
        PolicyVersion published = version.transitionTo(PolicyStatus.PUBLISHED, publisherId, effectiveFrom, now);
        PolicyVersion saved = policyVersionRepository.save(published);

        Policy policy = policyRepository.findById(version.policyId())
            .orElseThrow(() -> new PolicyNotFoundException(version.policyId()));
        Integer previouslyPublishedVersionNumber = policy.currentPublishedVersion();
        policyRepository.save(policy.withCurrentPublishedVersion(version.versionNumber(), now));

        auditService.record(
            GovernanceAuditRecord.Action.POLICY_PUBLISHED, publisherId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version published", correlationId, null,
            null, null, null,
            PolicyPublishedEvent.from(saved, correlationId, null)
        );
        metrics.recordPolicyPublished();
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("policyVersion", saved.versionNumber())
            .log("policy version published");

        if (previouslyPublishedVersionNumber != null && previouslyPublishedVersionNumber != version.versionNumber()) {
            supersedeIfStillPublished(version.policyId(), previouslyPublishedVersionNumber, publisherId, version.versionNumber(), now, correlationId);
        }
        return saved;
    }

    private void supersedeIfStillPublished(
        String policyId, int previousVersionNumber, String actorId, int newVersionNumber, Instant now, String correlationId
    ) {
        policyVersionRepository.findByPolicyIdAndVersionNumber(policyId, previousVersionNumber)
            .filter(previous -> previous.status() == PolicyStatus.PUBLISHED)
            .ifPresent(previous -> {
                PolicyVersion superseded = previous.transitionTo(PolicyStatus.SUPERSEDED, actorId, null, now);
                policyVersionRepository.save(superseded);
                auditService.record(
                    GovernanceAuditRecord.Action.POLICY_SUPERSEDED, actorId, "06", policyId, policyId,
                    String.valueOf(previousVersionNumber),
                    "policy version superseded by version " + newVersionNumber, correlationId, null,
                    null, null, null
                );
            });
    }

    @Transactional
    public PolicyVersion deprecate(String policyVersionId, String actorId, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        PolicyVersion deprecated = version.transitionTo(PolicyStatus.DEPRECATED, actorId, null, clock.instant());
        PolicyVersion saved = policyVersionRepository.save(deprecated);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_DEPRECATED, actorId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version deprecated", correlationId, null,
            null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("policyVersion", saved.versionNumber())
            .log("policy version deprecated");
        return saved;
    }

    /** SPEC-PG-020 (goal: "archive... states"): the terminal {@code DEPRECATED -> ARCHIVED} transition (03-state-machine). */
    @Transactional
    public PolicyVersion archive(String policyVersionId, String actorId, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        PolicyVersion archived = version.transitionTo(PolicyStatus.ARCHIVED, actorId, null, clock.instant());
        PolicyVersion saved = policyVersionRepository.save(archived);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_ARCHIVED, actorId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version archived", correlationId, null,
            null, null, null
        );
        log.atInfo()
            .addKeyValue("correlationId", correlationId)
            .addKeyValue("policyVersion", saved.versionNumber())
            .log("policy version archived");
        return saved;
    }

    private PolicyVersion requireVersion(String policyVersionId) {
        return policyVersionRepository.findById(policyVersionId)
            .orElseThrow(() -> new PolicyVersionNotFoundException(policyVersionId));
    }
}
