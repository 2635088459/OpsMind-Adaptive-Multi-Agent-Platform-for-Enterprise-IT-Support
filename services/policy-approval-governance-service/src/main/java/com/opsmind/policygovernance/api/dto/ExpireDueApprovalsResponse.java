package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.approval.ApprovalRequest;

import java.util.List;

/** SPEC-PG-012: response shape for {@code POST /api/v1/approval-requests:expire-due}. */
public record ExpireDueApprovalsResponse(int expiredCount, List<String> approvalRequestIds) {

    public static ExpireDueApprovalsResponse from(List<ApprovalRequest> expired) {
        return new ExpireDueApprovalsResponse(expired.size(), expired.stream().map(ApprovalRequest::approvalRequestId).toList());
    }
}
