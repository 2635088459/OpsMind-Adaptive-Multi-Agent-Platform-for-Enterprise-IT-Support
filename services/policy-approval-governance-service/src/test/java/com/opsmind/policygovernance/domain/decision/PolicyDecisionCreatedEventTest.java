package com.opsmind.policygovernance.domain.decision;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-PG-028: mirrors {@code ApprovalRequestedEventTest}'s own style. */
@Tag("unit")
class PolicyDecisionCreatedEventTest {

    private PolicyDecision decision() {
        return new PolicyDecision(
            "pd-1", "dk-1", "hash-1", "subject-type", "subject-1", "action-1",
            "resource-type", "resource-1", "tenant-1", "tool-gateway", "src-req-1", "ticket-1", null,
            DecisionEffect.ALLOW, RiskLevel.LOW, false, false, List.of(), List.of(ReasonCode.POLICY_MATCHED),
            "policy-1", "1", Instant.now(), null, false
        );
    }

    @Test
    void carriesTheRealEventTypeAndTheDecisionsOwnAggregateIdentity() {
        PolicyDecisionCreatedEvent event = PolicyDecisionCreatedEvent.from(decision(), "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("policy.decision.created.v1");
        assertThat(event.aggregateType()).isEqualTo("PolicyDecision");
        assertThat(event.aggregateId()).isEqualTo("pd-1");
        assertThat(event.ticketId()).isEqualTo("ticket-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.causationId()).isEqualTo("cause-1");
        assertThat(event.payload())
            .containsEntry("policyDecisionId", "pd-1")
            .containsEntry("decisionKey", "dk-1")
            .containsEntry("sourceDomain", "tool-gateway")
            .containsEntry("sourceRequestId", "src-req-1")
            .containsEntry("effect", "ALLOW")
            .containsEntry("riskLevel", "LOW")
            .containsEntry("approvalRequired", false)
            .containsEntry("evaluationFailed", false)
            .containsEntry("degraded", false)
            .containsEntry("policyId", "policy-1")
            .containsEntry("policyVersion", "1");
        assertThat(event.payload().get("reasonCodes")).isEqualTo(List.of("POLICY_MATCHED"));
    }
}
