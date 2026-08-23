package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.domain.approval.ApprovalExpiredEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Expires {@code REQUESTED} approval requests once past {@code expiresAt}
 * (03-state-machine: {@code REQUESTED -> EXPIRED}), keeping "expired"
 * distinct from "denied"/"cancelled" per INV-PG-007 — 10-failure-handling
 * §Approval Timeout: "Expiry worker moves it to EXPIRED; publishes
 * approval.expired.v1; downstream decides retry, human intervention, or
 * cancellation." SPEC-PG-012 owns this class's invocation surface: {@code
 * api.ApprovalExpiryController} exposes it as an admin-triggerable endpoint
 * rather than a {@code @Scheduled} trigger, the same "call it from an admin
 * endpoint or an external scheduler" seam {@code OutboxDispatchService}'s
 * own javadoc documents (a repeatedly-confirmed platform-wide scope
 * boundary: no in-process scheduled loop anywhere in this codebase) — an
 * external scheduler (e.g. a Kubernetes CronJob) is expected to call that
 * endpoint on a fixed cadence.
 */
@Service
public class ApprovalExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryService.class);

    private final ApprovalRequestRepository approvalRequestRepository;
    private final GovernanceAuditService auditService;
    private final GovernanceMetricsPort metrics;
    private final Clock clock;

    public ApprovalExpiryService(
        ApprovalRequestRepository approvalRequestRepository, GovernanceAuditService auditService, GovernanceMetricsPort metrics, Clock clock
    ) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * SPEC-PG-012 (10-failure-handling): one bad row (e.g. a concurrent
     * grant/deny raced this scan and already moved the request out of
     * {@code REQUESTED}) must not fail the rest of the batch. Mirrors {@code
     * OutboxDispatchService#publishPending}'s own established pattern in
     * this codebase — catch per-item, log, and continue — rather than a
     * literal per-row transaction, since the whole scan still commits as one
     * transaction and a caught exception here never propagates out to mark
     * that transaction rollback-only.
     */
    @Transactional
    public List<ApprovalRequest> expireDue() {
        Instant now = clock.instant();
        List<ApprovalRequest> due = approvalRequestRepository.findRequestedExpiringBefore(now);
        List<ApprovalRequest> expired = new ArrayList<>();
        for (ApprovalRequest request : due) {
            try {
                ApprovalRequest saved = approvalRequestRepository.save(request.expire(now));
                auditService.record(
                    GovernanceAuditRecord.Action.APPROVAL_EXPIRED, "system", request.sourceDomain(),
                    request.sourceRequestId(), null, null, "approval request expired", request.approvalRequestId(), null,
                    ApprovalExpiredEvent.from(saved, request.approvalRequestId(), null)
                );
                metrics.recordApprovalExpired();
                expired.add(saved);
            } catch (RuntimeException e) {
                log.warn(
                    "failed to expire approval request {}, skipping (will be retried on the next scan): {}",
                    request.approvalRequestId(), e.getMessage()
                );
            }
        }
        return expired;
    }
}
