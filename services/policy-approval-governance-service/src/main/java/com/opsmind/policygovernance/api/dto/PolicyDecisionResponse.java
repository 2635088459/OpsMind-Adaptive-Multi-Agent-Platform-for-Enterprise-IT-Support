package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Instant;
import java.util.List;

/**
 * api-contract: "Decision API returns governance facts and performs no
 * business side effects." / "conflict, denied, expired, cancelled, and
 * evaluation failed must remain distinguishable" — see {@code
 * evaluationFailed}.
 */
public record PolicyDecisionResponse(
    String policyDecisionId,
    String decisionKey,
    String inputHash,
    String sourceDomain,
    String sourceRequestId,
    String ticketId,
    String workflowInstanceId,
    DecisionEffect effect,
    RiskLevel riskLevel,
    boolean approvalRequired,
    boolean evaluationFailed,
    List<ConstraintDto> constraints,
    List<ReasonCode> reasonCodes,
    String policyId,
    String policyVersion,
    Instant evaluatedAt,
    Instant expiresAt,
    /** SPEC-PG-021 (10-failure-handling §Degraded Policy Mode): true only when an effective version existed but the evaluator itself threw. */
    boolean degraded
) {

    public static PolicyDecisionResponse from(PolicyDecision decision) {
        return new PolicyDecisionResponse(
            decision.policyDecisionId(), decision.decisionKey(), decision.inputHash(),
            decision.sourceDomain(), decision.sourceRequestId(), decision.ticketId(), decision.workflowInstanceId(),
            decision.effect(), decision.riskLevel(), decision.approvalRequired(), decision.evaluationFailed(),
            decision.constraints().stream().map(ConstraintDto::from).toList(),
            decision.reasonCodes(), decision.policyId(), decision.policyVersion(),
            decision.evaluatedAt(), decision.expiresAt(), decision.degraded()
        );
    }
}
