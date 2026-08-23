package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Expires {@code REQUESTED} approval requests once past {@code expiresAt}
 * (03-state-machine: {@code REQUESTED -> EXPIRED}), keeping "expired"
 * distinct from "denied"/"cancelled" per INV-PG-007. SPEC-PG-012 (Approval
 * Expiry Cancel, 10-failure-handling) owns the scheduled trigger that calls
 * {@link #expireDue()} on a fixed cadence and the transactional-outbox
 * publication of {@code approval.expired.v1}; this class is the callable use
 * case that trigger will invoke.
 */
@Service
public class ApprovalExpiryService {

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
     * The whole scan commits as one transaction for now — SPEC-PG-012
     * (10-failure-handling) owns per-request failure isolation once a real
     * scheduled trigger drives this at volume; a single bad row failing the
     * entire batch is an acceptable, honestly-scoped limitation until then.
     */
    @Transactional
    public List<ApprovalRequest> expireDue() {
        Instant now = clock.instant();
        List<ApprovalRequest> due = approvalRequestRepository.findRequestedExpiringBefore(now);
        List<ApprovalRequest> expired = new ArrayList<>();
        for (ApprovalRequest request : due) {
            ApprovalRequest saved = approvalRequestRepository.save(request.expire(now));
            auditService.record(
                GovernanceAuditRecord.Action.APPROVAL_EXPIRED, "system", request.sourceDomain(),
                request.sourceRequestId(), null, null, "approval request expired", request.approvalRequestId(), null
            );
            metrics.recordApprovalExpired();
            expired.add(saved);
        }
        return expired;
    }
}
