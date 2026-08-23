package com.opsmind.policygovernance.domain.decision;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyDecisionTest {

    private PolicyDecision decision(List<ReasonCode> reasonCodes) {
        return new PolicyDecision(
            "pd-1", "decision-key-1", "hash-1", "user", "user-1", "READ",
            "ticket", "ticket-1", "tenant-1", "tool-gateway", "src-req-1", "ticket-1", null,
            DecisionEffect.ALLOW, RiskLevel.LOW,
            false, false, List.of(), reasonCodes, "policy-1", "3", Instant.now(), null
        );
    }

    @Test
    void requiresAtLeastOneReasonCode() {
        assertThatThrownBy(() -> decision(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isExplainable() {
        PolicyDecision decision = decision(List.of(ReasonCode.POLICY_MATCHED));
        assertThat(decision.inputHash()).isNotBlank();
        assertThat(decision.policyId()).isNotBlank();
        assertThat(decision.policyVersion()).isNotBlank();
        assertThat(decision.evaluatedAt()).isNotNull();
        assertThat(decision.reasonCodes()).containsExactly(ReasonCode.POLICY_MATCHED);
    }

    @Test
    void reasonCodesAreNotModifiableAfterConstruction() {
        List<ReasonCode> mutable = new ArrayList<>(List.of(ReasonCode.POLICY_MATCHED));
        PolicyDecision decision = decision(mutable);
        mutable.add(ReasonCode.OVERRIDE_APPLIED);

        assertThat(decision.reasonCodes()).containsExactly(ReasonCode.POLICY_MATCHED);
        assertThatThrownBy(() -> decision.reasonCodes().add(ReasonCode.OVERRIDE_APPLIED))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
