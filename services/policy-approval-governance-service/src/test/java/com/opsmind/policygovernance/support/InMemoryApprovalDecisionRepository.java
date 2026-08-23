package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.ApprovalDecisionRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, in-process test double for {@link ApprovalDecisionRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryApprovalDecisionRepository implements ApprovalDecisionRepository {

    private final Map<String, ApprovalDecision> byApprovalRequestId = new ConcurrentHashMap<>();

    @Override
    public ApprovalDecision save(ApprovalDecision decision) {
        byApprovalRequestId.put(decision.approvalRequestId(), decision);
        return decision;
    }

    @Override
    public Optional<ApprovalDecision> findByApprovalRequestId(String approvalRequestId) {
        return Optional.ofNullable(byApprovalRequestId.get(approvalRequestId));
    }
}
