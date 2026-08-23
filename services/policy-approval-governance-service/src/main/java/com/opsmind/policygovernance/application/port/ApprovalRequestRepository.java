package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port for {@link ApprovalRequest} persistence. */
public interface ApprovalRequestRepository {

    ApprovalRequest save(ApprovalRequest approvalRequest);

    Optional<ApprovalRequest> findById(String approvalRequestId);

    /**
     * Same lookup as {@link #findById}, but takes a pessimistic write lock
     * on the row (08-transaction-and-outbox §Approval Decision step 1:
     * "Lock ApprovalRequest using SELECT ... FOR UPDATE"). A concurrent
     * grant/deny on the same request blocks here instead of racing, so
     * {@code ApprovalService#decide} can tell an idempotent replay from a
     * genuine conflict instead of surfacing a raw constraint violation.
     */
    Optional<ApprovalRequest> findByIdForUpdate(String approvalRequestId);

    /** Used for approval-request idempotency: a repeated {@code requestKey} must not open a second request. */
    Optional<ApprovalRequest> findByRequestKey(String requestKey);

    /** Used by the expiry scan (SPEC-PG-012 owns the scheduled trigger; this port supports it from PG-001 on). */
    List<ApprovalRequest> findRequestedExpiringBefore(Instant threshold);
}
