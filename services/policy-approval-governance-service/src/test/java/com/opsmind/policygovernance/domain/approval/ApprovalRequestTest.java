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
            null, null, "tool-req-1", null, "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION,
            RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    private ApprovalDecision approvedDecisionFor(ApprovalRequest request) {
        return new ApprovalDecision(
            "ad-1", request.approvalRequestId(), ApprovalDecision.Outcome.APPROVED,
            "approver-1", Instant.now(), "looks fine", List.of(), true, "cik-1", null, null, true
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
        ApprovalRequest cancelled = requested().cancel(Instant.now(), "cik-1");
        assertThatThrownBy(() -> cancelled.expire(Instant.now())).isInstanceOf(IllegalApprovalTransitionException.class);
        assertThatThrownBy(() -> cancelled.cancel(Instant.now(), "cik-2")).isInstanceOf(IllegalApprovalTransitionException.class);
    }

    /** SPEC-PG-012: {@code cancelCommandIdempotencyKey} survives the cancel transition so {@code ApprovalService#cancel} can compare it on a retry. */
    @Test
    void cancelStoresItsOwnCommandIdempotencyKey() {
        ApprovalRequest cancelled = requested().cancel(Instant.now(), "cik-1");
        assertThat(cancelled.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(cancelled.cancelCommandIdempotencyKey()).isEqualTo("cik-1");
    }

    @Test
    void decisionMustTargetThisExactRequest() {
        ApprovalRequest request = requested();
        ApprovalDecision decisionForAnotherRequest = new ApprovalDecision(
            "ad-2", "some-other-request-id", ApprovalDecision.Outcome.APPROVED,
            "approver-1", Instant.now(), "wrong request", List.of(), true, "cik-1", null, null, true
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
            null, null, "tool-req-2", null, null, "requester-1", ApprovalType.TOOL_EXECUTION,
            RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );

        assertThat(request.policyDecisionId()).isNull();
    }

    /**
     * SPEC-PG-015 (11-security §Separation Of Duties): {@code executorId}
     * must survive construction and transitions the same way {@code
     * policyDecisionId} does, and stays {@code null} when the caller never
     * supplied one — 06 must not fabricate an executor identity it was
     * never given.
     */
    @Test
    void carriesTheExecutorIdThroughConstructionAndTransitions() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-3", "req-key-3", "hash-3", "tool-gateway", "src-req-3",
            null, null, "tool-req-3", "executor-1", "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION,
            RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
        assertThat(request.executorId()).isEqualTo("executor-1");

        ApprovalRequest approved = request.approve(approvedDecisionFor(request), Instant.now());
        assertThat(approved.executorId()).isEqualTo("executor-1");
    }

    @Test
    void executorIdIsNullWhenTheCallerNeverSuppliedOne() {
        assertThat(requested().executorId()).isNull();
    }

    private ApprovalRequest approvedOverride(Instant expiresAt) {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-override-1", "req-key-override-1", "hash-override-1", "tool-gateway", "src-req-override-1",
            null, null, null, null, null, "requester-1", ApprovalType.POLICY_OVERRIDE,
            RiskLevel.CRITICAL, List.of(), expiresAt, Instant.now()
        );
        return request.approve(approvedDecisionFor(request), Instant.now());
    }

    /** SPEC-PG-022: {@code OVERRIDE_APPROVED -> OVERRIDE_USED} is legal for an approved POLICY_OVERRIDE request still within its time window. */
    @Test
    void useTransitionsAnApprovedOverrideToUsed() {
        ApprovalRequest approved = approvedOverride(Instant.now().plusSeconds(3600));

        ApprovalRequest used = approved.use(Instant.now(), "use-key-1");

        assertThat(used.status()).isEqualTo(ApprovalStatus.USED);
        assertThat(used.usedCommandIdempotencyKey()).isEqualTo("use-key-1");
    }

    /** SPEC-PG-022: use is only legal from APPROVED. */
    @Test
    void useIsRejectedWhenTheRequestIsNotApproved() {
        ApprovalRequest requested = ApprovalRequest.requested(
            "ar-override-2", "req-key-override-2", "hash-override-2", "tool-gateway", "src-req-override-2",
            null, null, null, null, null, "requester-1", ApprovalType.POLICY_OVERRIDE,
            RiskLevel.CRITICAL, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );

        assertThatThrownBy(() -> requested.use(Instant.now(), "use-key-1"))
            .isInstanceOf(IllegalApprovalTransitionException.class);
    }

    /** SPEC-PG-022: use/revoke are override-specific — an ordinary approval type has no OVERRIDE_USED/OVERRIDE_REVOKED continuation. */
    @Test
    void useIsRejectedForANonOverrideApprovalType() {
        ApprovalRequest request = requested();
        ApprovalRequest approved = request.approve(approvedDecisionFor(request), Instant.now());

        assertThatThrownBy(() -> approved.use(Instant.now(), "use-key-1"))
            .isInstanceOf(NotAnOverrideRequestException.class);
    }

    /** SPEC-PG-022 (UC-PG-006: "valid only within limited scope and time window"): use rejects a lapsed override even if still nominally APPROVED. */
    @Test
    void useIsRejectedOncePastExpiresAt() {
        ApprovalRequest approved = approvedOverride(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> approved.use(Instant.now(), "use-key-1"))
            .isInstanceOf(OverrideExpiredException.class);
    }

    /** SPEC-PG-022: {@code OVERRIDE_APPROVED -> OVERRIDE_REVOKED} is legal for an approved POLICY_OVERRIDE request. */
    @Test
    void revokeTransitionsAnApprovedOverrideToRevoked() {
        ApprovalRequest approved = approvedOverride(Instant.now().plusSeconds(3600));

        ApprovalRequest revoked = approved.revoke(Instant.now(), "revoke-key-1");

        assertThat(revoked.status()).isEqualTo(ApprovalStatus.REVOKED);
        assertThat(revoked.revokedCommandIdempotencyKey()).isEqualTo("revoke-key-1");
    }

    /** SPEC-PG-022: unlike use, an already-lapsed expiresAt does not block revoke — formally closing out a stale override is still legitimate. */
    @Test
    void revokeIsNotBlockedByAnAlreadyLapsedExpiresAt() {
        ApprovalRequest approved = approvedOverride(Instant.now().minusSeconds(1));

        ApprovalRequest revoked = approved.revoke(Instant.now(), "revoke-key-1");

        assertThat(revoked.status()).isEqualTo(ApprovalStatus.REVOKED);
    }

    @Test
    void revokeIsRejectedForANonOverrideApprovalType() {
        ApprovalRequest request = requested();
        ApprovalRequest approved = request.approve(approvedDecisionFor(request), Instant.now());

        assertThatThrownBy(() -> approved.revoke(Instant.now(), "revoke-key-1"))
            .isInstanceOf(NotAnOverrideRequestException.class);
    }

    /** SPEC-PG-022: USED and REVOKED are themselves final and irreversible, the same as every other non-REQUESTED status. */
    @Test
    void usedAndRevokedAreThemselvesFinal() {
        ApprovalRequest used = approvedOverride(Instant.now().plusSeconds(3600)).use(Instant.now(), "use-key-1");
        assertThatThrownBy(() -> used.revoke(Instant.now(), "revoke-key-1")).isInstanceOf(IllegalApprovalTransitionException.class);

        ApprovalRequest revoked = approvedOverride(Instant.now().plusSeconds(3600)).revoke(Instant.now(), "revoke-key-1");
        assertThatThrownBy(() -> revoked.use(Instant.now(), "use-key-1")).isInstanceOf(IllegalApprovalTransitionException.class);
    }
}
