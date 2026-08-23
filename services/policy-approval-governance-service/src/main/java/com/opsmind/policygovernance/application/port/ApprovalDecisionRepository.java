package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalDecision;

import java.util.Optional;

/** Port for {@link ApprovalDecision} persistence. */
public interface ApprovalDecisionRepository {

    ApprovalDecision save(ApprovalDecision decision);

    Optional<ApprovalDecision> findByApprovalRequestId(String approvalRequestId);
}
