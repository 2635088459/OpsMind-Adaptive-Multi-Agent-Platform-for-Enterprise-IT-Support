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
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * UC-PG-005 (Policy Publication): draft -> review -> publish -> deprecate.
 * {@link #publish} enforces the test-plan security rule "policy author
 * cannot publish their own unreviewed policy"; the fuller reviewer/publisher
 * role model belongs to phase-04 (Policy Admin And Versioning) — this only
 * refuses the narrowest, always-true case (author == publisher).
 */
@Service
public class PolicyAdminService {

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

    @Transactional
    public PolicyVersion draft(DraftPolicyCommand command) {
        Policy policy = policyRepository.findById(command.policyId())
            .orElseGet(() -> policyRepository.save(
                Policy.created(command.policyId(), command.policyName(), command.scope(), command.createdBy(), clock.instant())
            ));

        // PG-001 skeleton: a single draft per policyId, always version 1. Real
        // multi-version numbering (superseding a prior PUBLISHED version) is
        // owned by phase-04's policy admin specs.
        PolicyVersion draft = PolicyVersion.draft(
            UUID.randomUUID().toString(), policy.policyId(), 1, command.rules(), command.createdBy()
        );
        PolicyVersion saved = policyVersionRepository.save(draft);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_DRAFTED, command.createdBy(), "06", policy.policyId(),
            policy.policyId(), String.valueOf(saved.versionNumber()), "policy version drafted", command.correlationId(), null
        );
        return saved;
    }

    @Transactional
    public PolicyVersion review(String policyVersionId, String reviewerId, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        PolicyVersion reviewed = version.transitionTo(PolicyStatus.REVIEWING, reviewerId, null, clock.instant());
        PolicyVersion saved = policyVersionRepository.save(reviewed);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_REVIEWED, reviewerId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version under review", correlationId, null
        );
        return saved;
    }

    @Transactional
    public PolicyVersion publish(String policyVersionId, String publisherId, Instant effectiveFrom, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        if (version.createdBy().equals(publisherId)) {
            throw new PolicyPublishSeparationOfDutiesException(policyVersionId);
        }
        Instant now = clock.instant();
        PolicyVersion published = version.transitionTo(PolicyStatus.PUBLISHED, publisherId, effectiveFrom, now);
        PolicyVersion saved = policyVersionRepository.save(published);

        Policy policy = policyRepository.findById(version.policyId())
            .orElseThrow(() -> new PolicyNotFoundException(version.policyId()));
        policyRepository.save(policy.withCurrentPublishedVersion(version.versionNumber(), now));

        auditService.record(
            GovernanceAuditRecord.Action.POLICY_PUBLISHED, publisherId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version published", correlationId, null
        );
        metrics.recordPolicyPublished();
        return saved;
    }

    @Transactional
    public PolicyVersion deprecate(String policyVersionId, String actorId, String correlationId) {
        PolicyVersion version = requireVersion(policyVersionId);
        PolicyVersion deprecated = version.transitionTo(PolicyStatus.DEPRECATED, actorId, null, clock.instant());
        PolicyVersion saved = policyVersionRepository.save(deprecated);
        auditService.record(
            GovernanceAuditRecord.Action.POLICY_DEPRECATED, actorId, "06", version.policyId(),
            version.policyId(), String.valueOf(version.versionNumber()), "policy version deprecated", correlationId, null
        );
        return saved;
    }

    private PolicyVersion requireVersion(String policyVersionId) {
        return policyVersionRepository.findById(policyVersionId)
            .orElseThrow(() -> new PolicyVersionNotFoundException(policyVersionId));
    }
}
