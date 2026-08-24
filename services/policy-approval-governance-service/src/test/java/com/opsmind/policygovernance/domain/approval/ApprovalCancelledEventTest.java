package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-PG-035 (goal: "cross-domain contract tests with 02/03/04/05"): {@code approval.cancelled.v1} (SPEC-PG-012) never had its own shape test until this spec. */
@Tag("unit")
class ApprovalCancelledEventTest {

    private ApprovalRequest requested(String approvalRequestId, String ticketId) {
        return ApprovalRequest.requested(
            approvalRequestId, "rk-" + approvalRequestId, "hash-1", "tool-gateway", "src-req-1", ticketId, "wf-1",
            "tool-req-1", null, "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    @Test
    void carriesTheRealEventTypeAndTheRequestsOwnAggregateIdentity() {
        ApprovalRequest request = requested("ar-1", "ticket-1");

        ApprovalCancelledEvent event = ApprovalCancelledEvent.from(request, "no longer needed", "requester-1", "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("approval.cancelled.v1");
        assertThat(event.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(event.aggregateId()).isEqualTo("ar-1");
        assertThat(event.ticketId()).isEqualTo("ticket-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.causationId()).isEqualTo("cause-1");
        assertThat(event.payload())
            .containsEntry("approvalRequestId", "ar-1")
            .containsEntry("sourceDomain", "tool-gateway")
            .containsEntry("sourceRequestId", "src-req-1")
            .containsEntry("requestHash", "hash-1")
            .containsEntry("workflowInstanceId", "wf-1")
            .containsEntry("toolRequestId", "tool-req-1")
            .containsEntry("policyDecisionId", "pd-1")
            .containsEntry("cancelledBy", "requester-1")
            .containsEntry("reason", "no longer needed");
    }

    /** INV-PG-007: cancelled must stay a distinguishable fact from expired/denied — {@code cancelledBy}/{@code reason} are unique to a cancel. */
    @Test
    void toleratesAbsentOptionalFields() {
        ApprovalRequest bare = ApprovalRequest.requested(
            "ar-2", "rk-2", "hash-2", "tool-gateway", "src-req-2", null, null, "tool-req-2", null,
            null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );

        ApprovalCancelledEvent event = ApprovalCancelledEvent.from(bare, "no longer needed", "requester-1", "corr-1", null);

        assertThat(event.ticketId()).isNull();
        assertThat(event.payload())
            .containsEntry("workflowInstanceId", null)
            .containsEntry("policyDecisionId", null);
    }
}
