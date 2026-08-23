package com.opsmind.policygovernance.domain.approval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ApprovalDecisionTest {

    @Test
    void anApprovedOutcomeRequiresAPassedSeparationOfDutiesCheck() {
        assertThatThrownBy(() -> new ApprovalDecision(
            "ad-1", "ar-1", ApprovalDecision.Outcome.APPROVED,
            "approver-1", Instant.now(), "reason", List.of(), false, "cik-1"
        )).isInstanceOf(SeparationOfDutiesNotVerifiedException.class);
    }

    @Test
    void aDeniedOutcomeDoesNotRequireSeparationOfDutiesCheck() {
        // Should not throw.
        new ApprovalDecision(
            "ad-1", "ar-1", ApprovalDecision.Outcome.DENIED,
            "approver-1", Instant.now(), "reason", List.of(), false, "cik-1"
        );
    }

    @Test
    void requiresANonBlankDecidedBy() {
        assertThatThrownBy(() -> new ApprovalDecision(
            "ad-1", "ar-1", ApprovalDecision.Outcome.DENIED,
            "  ", Instant.now(), "reason", List.of(), false, "cik-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresANonBlankReason() {
        assertThatThrownBy(() -> new ApprovalDecision(
            "ad-1", "ar-1", ApprovalDecision.Outcome.DENIED,
            "approver-1", Instant.now(), "", List.of(), false, "cik-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    /** SPEC-PG-011: 09-concurrency-and-idempotency's approval-command idempotency key is mandatory. */
    @Test
    void requiresANonBlankCommandIdempotencyKey() {
        assertThatThrownBy(() -> new ApprovalDecision(
            "ad-1", "ar-1", ApprovalDecision.Outcome.DENIED,
            "approver-1", Instant.now(), "reason", List.of(), false, " "
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
