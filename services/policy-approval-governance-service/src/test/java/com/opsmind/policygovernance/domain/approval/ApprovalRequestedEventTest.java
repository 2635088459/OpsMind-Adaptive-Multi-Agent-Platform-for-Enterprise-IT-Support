package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ApprovalRequestedEventTest {

    @Test
    void carriesTheRealEventTypeAndTheRequestsOwnAggregateIdentity() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", "ticket-1", null, "tool-req-1",
            "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );

        ApprovalRequestedEvent event = ApprovalRequestedEvent.from(request, "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("approval.requested.v1");
        assertThat(event.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(event.aggregateId()).isEqualTo("ar-1");
        assertThat(event.ticketId()).isEqualTo("ticket-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.causationId()).isEqualTo("cause-1");
        assertThat(event.payload())
            .containsEntry("approvalRequestId", "ar-1")
            .containsEntry("requestKey", "rk-1")
            .containsEntry("sourceDomain", "tool-gateway")
            .containsEntry("sourceRequestId", "src-req-1")
            .containsEntry("policyDecisionId", "pd-1")
            .containsEntry("approvalType", "TOOL_EXECUTION")
            .containsEntry("riskLevel", "HIGH");
    }

    /**
     * Several {@link ApprovalRequest} fields are legitimately absent
     * (workflowInstanceId/policyDecisionId here); the payload must not
     * throw building around a null value.
     */
    @Test
    void toleratesAbsentOptionalFieldsInThePayload() {
        ApprovalRequest request = ApprovalRequest.requested(
            "ar-2", "rk-2", "hash-2", "tool-gateway", "src-req-2", null, null, "tool-req-2",
            null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );

        ApprovalRequestedEvent event = ApprovalRequestedEvent.from(request, "corr-1", null);

        assertThat(event.ticketId()).isNull();
        assertThat(event.payload()).containsEntry("workflowInstanceId", null).containsEntry("policyDecisionId", null);
    }
}
