package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-PG-035 (goal: "cross-domain contract tests with 02/03/04/05"). The
 * one shape check every other real, graduated domain event in this
 * codebase already had ({@link ApprovalGrantedEventTest}, {@link
 * ApprovalDeniedEventTest}) — {@code approval.expired.v1} (SPEC-PG-012)
 * never had its own until this spec.
 */
@Tag("unit")
class ApprovalExpiredEventTest {

    private ApprovalRequest requested(String approvalRequestId, String ticketId, Instant expiresAt) {
        return ApprovalRequest.requested(
            approvalRequestId, "rk-" + approvalRequestId, "hash-1", "tool-gateway", "src-req-1", ticketId, "wf-1",
            "tool-req-1", null, "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            expiresAt, Instant.now()
        );
    }

    @Test
    void carriesTheRealEventTypeAndTheRequestsOwnAggregateIdentity() {
        Instant expiresAt = Instant.now().minusSeconds(10);
        ApprovalRequest request = requested("ar-1", "ticket-1", expiresAt).expire(Instant.now());

        ApprovalExpiredEvent event = ApprovalExpiredEvent.from(request, "corr-1", "cause-1");

        assertThat(event.eventType()).isEqualTo("approval.expired.v1");
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
            .containsEntry("expiresAt", expiresAt.toString());
    }

    /** INV-PG-007: expired must stay a distinguishable fact from denied/cancelled — this event's own type name is the mechanism. */
    @Test
    void toleratesAbsentOptionalFields() {
        ApprovalRequest request = requested("ar-2", null, Instant.now().minusSeconds(10)).expire(Instant.now());
        ApprovalRequest bare = ApprovalRequest.requested(
            "ar-3", "rk-3", "hash-3", "tool-gateway", "src-req-3", null, null, "tool-req-3", null,
            null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().minusSeconds(10), Instant.now()
        ).expire(Instant.now());

        ApprovalExpiredEvent event = ApprovalExpiredEvent.from(request, "corr-1", null);
        ApprovalExpiredEvent bareEvent = ApprovalExpiredEvent.from(bare, "corr-1", null);

        assertThat(event.ticketId()).isNull();
        assertThat(bareEvent.payload())
            .containsEntry("workflowInstanceId", null)
            .containsEntry("policyDecisionId", null);
    }
}
