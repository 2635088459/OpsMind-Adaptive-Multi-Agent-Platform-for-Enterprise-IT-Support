package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ApprovalRequestTest {

    private ApprovalRequest requested() {
        return ApprovalRequest.requested(
            "ar-1", "req-key-1", "hash-1", "tool-gateway", "src-req-1",
            null, null, "tool-req-1", "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION,
            RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    private ApprovalDecision approvedDecisionFor(ApprovalRequest request) {
        return new ApprovalDecision(
            "ad-1", request.approvalRequestId(), ApprovalDecision.Outcome.APPROVED,
            "approver-1", Instant.now(), "looks fine", List.of(), true, "cik-1"
        );
    }

    @Test
    void onlyRequestedCanBeApproved() {
        ApprovalRequest request = requested();
        ApprovalRequest approved = request.approve(approvedDecisionFor(request), Instant.now());

        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThatThrownBy(() -> approved.approve(approvedDecisionFor(approved), Instant.now()))
            .isInstanceOf(IllegalApprovalTransitionException.class);
    }

    @Test
    void finalStatesAreIrreversible() {
        ApprovalRequest cancelled = requested().cancel(Instant.now());
        assertThatThrownBy(() -> cancelled.expire(Instant.now())).isInstanceOf(IllegalApprovalTransitionException.class);
        assertThatThrownBy(() -> cancelled.cancel(Instant.now())).isInstanceOf(IllegalApprovalTransitionException.class);
    }

    @Test
    void decisionMustTargetThisExactRequest() {
        ApprovalRequest request = requested();
        ApprovalDecision decisionForAnotherRequest = new ApprovalDecision(
            "ad-2", "some-other-request-id", ApprovalDecision.Outcome.APPROVED,
            "approver-1", Instant.now(), "wrong request", List.of(), true, "cik-1"
        );

        assertThatThrownBy(() -> request.approve(decisionForAnotherRequest, Instant.now()))
            .isInstanceOf(ApprovalRequestMismatchException.class);
    }

    @Test
    void matchesOnlyTheExactSourceRequestIdAndRequestHash() {
        ApprovalRequest request = requested();
        assertThat(request.matches("src-req-1", "hash-1")).isTrue();
        assertThat(request.matches("src-req-1", "wrong-hash")).isFalse();
        assertThat(request.matches("wrong-src-req", "hash-1")).isFalse();
    }

    /**
     * SPEC-PG-009 / 01-domain-model §Aggregate Boundary: the optional
     * back-reference to the {@code PolicyDecision} this request originated
     * from must survive both construction and every terminal transition.
     */
    @Test
    void carriesThePolicyDecisionLinkThroughConstructionAndTransitions() {
        ApprovalRequest request = requested();
        assertThat(request.policyDecisionId()).isEqualTo("pd-1");

        ApprovalRequest approved = request.approve(approvedDecisionFor(request), Instant.now());
        assertThat(approved.policyDecisionId()).isEqualTo("pd-1");
    }

    @Test
    void policyDecisionLinkIsNullWhenTheRequestDidNotOriginateFromAPolicyEvaluation() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-2", "req-key-2", "hash-2", "tool-gateway", "src-req-2",
            null, null, "tool-req-2", null, "requester-1", ApprovalType.TOOL_EXECUTION,
            RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );

        assertThat(request.policyDecisionId()).isNull();
    }
}
