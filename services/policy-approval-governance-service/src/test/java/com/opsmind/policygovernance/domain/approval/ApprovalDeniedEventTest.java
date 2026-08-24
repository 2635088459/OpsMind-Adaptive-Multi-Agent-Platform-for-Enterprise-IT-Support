package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ApprovalDeniedEventTest {

    @Test
    void carriesTheRealEventTypeAndTheRequestsOwnAggregateIdentity() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", "ticket-1", null, "tool-req-1", null,
            "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        ApprovalDecision decision = new ApprovalDecision(
            "ad-1", request.approvalRequestId(), ApprovalDecision.Outcome.DENIED, "approver-1", Instant.now(),
            "too risky", List.of(), false, "cik-1", null, null, false
        );

        ApprovalDeniedEvent event = ApprovalDeniedEvent.from(request, decision, "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("approval.denied.v1");
        assertThat(event.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(event.aggregateId()).isEqualTo("ar-1");
        assertThat(event.ticketId()).isEqualTo("ticket-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.causationId()).isEqualTo("cause-1");
        assertThat(event.payload())
            .containsEntry("approvalRequestId", "ar-1")
            .containsEntry("sourceRequestId", "src-req-1")
            .containsEntry("requestHash", "hash-1")
            .containsEntry("decidedBy", "approver-1")
            .containsEntry("reason", "too risky")
            .containsEntry("conditions", List.of())
            .doesNotContainKey("separationOfDutiesCheck");
    }

    @Test
    void toleratesAbsentOptionalFieldsInThePayload() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-2", "rk-2", "hash-2", "tool-gateway", "src-req-2", null, null, "tool-req-2", null,
            null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        ApprovalDecision decision = new ApprovalDecision(
            "ad-2", request.approvalRequestId(), ApprovalDecision.Outcome.DENIED, "approver-1", Instant.now(),
            "too risky", List.of(), false, "cik-1", null, null, false
        );

        ApprovalDeniedEvent event = ApprovalDeniedEvent.from(request, decision, "corr-1", null);

        assertThat(event.ticketId()).isNull();
        assertThat(event.payload()).containsEntry("workflowInstanceId", null).containsEntry("policyDecisionId", null);
    }
}
