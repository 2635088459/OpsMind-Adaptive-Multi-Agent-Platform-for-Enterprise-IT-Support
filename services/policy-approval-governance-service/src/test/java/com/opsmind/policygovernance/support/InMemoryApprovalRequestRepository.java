package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, in-process test double for {@link ApprovalRequestRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryApprovalRequestRepository implements ApprovalRequestRepository {

    private final Map<String, ApprovalRequest> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByRequestKey = new ConcurrentHashMap<>();

    @Override
    public ApprovalRequest save(ApprovalRequest approvalRequest) {
        byId.put(approvalRequest.approvalRequestId(), approvalRequest);
        idByRequestKey.putIfAbsent(approvalRequest.requestKey(), approvalRequest.approvalRequestId());
        return approvalRequest;
    }

    @Override
    public Optional<ApprovalRequest> findById(String approvalRequestId) {
        return Optional.ofNullable(byId.get(approvalRequestId));
    }

    /** No real locking in-memory — single-threaded unit tests don't need it; see {@code GovernancePersistenceIT} for the real concurrency coverage. */
    @Override
    public Optional<ApprovalRequest> findByIdForUpdate(String approvalRequestId) {
        return findById(approvalRequestId);
    }

    @Override
    public Optional<ApprovalRequest> findByRequestKey(String requestKey) {
        return Optional.ofNullable(idByRequestKey.get(requestKey)).map(byId::get);
    }

    @Override
    public List<ApprovalRequest> findRequestedExpiringBefore(Instant threshold) {
        return byId.values().stream()
            .filter(r -> r.status() == ApprovalStatus.REQUESTED)
            .filter(r -> r.expiresAt() != null && r.expiresAt().isBefore(threshold))
            .toList();
    }
}
