package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.application.RecoveryService;

import java.util.List;

/** SPEC-PG-033: response shape for {@code POST /api/v1/admin/recovery:run}. */
public record RecoveryReportResponse(
    OutboxDispatchResponse outboxDispatch,
    int expiredApprovalsCount,
    List<PolicyVersionConsistencyFindingResponse> policyVersionConsistencyFindings,
    int poisonDecisionCount,
    List<String> deadLetteredOutboxIds
) {

    public static RecoveryReportResponse from(RecoveryService.RecoveryReport report) {
        return new RecoveryReportResponse(
            OutboxDispatchResponse.from(report.outboxDispatch()),
            report.expiredApprovalsCount(),
            report.policyVersionConsistencyFindings().stream().map(PolicyVersionConsistencyFindingResponse::from).toList(),
            report.poisonDecisionCount(),
            report.deadLetteredOutboxIds()
        );
    }
}
