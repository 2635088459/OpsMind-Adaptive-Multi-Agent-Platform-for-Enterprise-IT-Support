package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoApprovalApplied;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-018 domain-rules §1: {@code IN_PROGRESS -> IN_PROGRESS} self-transition and its invariants. */
@Tag("unit")
class TicketApplyAutoApprovedPolicyTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID APPROVAL_REQUEST_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-08-03T17:50:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketAutoApprovalApplied apply() {
        return Ticket.applyAutoApprovedPolicy(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 21L, APPROVAL_REQUEST_ID,
            "wf-9000", "act-100", "REFRESH_USER_SESSION", ApprovalRiskLevel.LOW,
            "policy-42", "1.0", "policy-dec-300", "auth-5678", DECIDED_AT, "evt-9", NOW
        );
    }

    @Test
    void shouldApplyAutoApprovedPolicyOnAnInProgressTicket() {
        TicketAutoApprovalApplied event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.actionType()).isEqualTo("REFRESH_USER_SESSION");
        assertThat(event.riskLevel()).isEqualTo(ApprovalRiskLevel.LOW);
        assertThat(event.policyId()).isEqualTo("policy-42");
        assertThat(event.policyVersion()).isEqualTo("1.0");
        assertThat(event.policyDecisionId()).isEqualTo("policy-dec-300");
        assertThat(event.authorizationReference()).isEqualTo("auth-5678");
        assertThat(event.decidedAt()).isEqualTo(DECIDED_AT);
        assertThat(event.autoApprovalEventId()).isEqualTo("evt-9");
        assertThat(event.transitionId()).isEqualTo("SM-020");
        assertThat(event.reasonCode()).isEqualTo("AUTO_APPROVAL_APPLIED");
        assertThat(event.aggregateVersion()).isEqualTo(22L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanInProgress(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyAutoApprovedPolicy(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 21L, APPROVAL_REQUEST_ID,
            "wf-9000", "act-100", "REFRESH_USER_SESSION", ApprovalRiskLevel.LOW,
            "policy-42", "1.0", "policy-dec-300", "auth-5678", DECIDED_AT, "evt-9", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyAutoApprovedPolicy(
            TICKET_ID, TicketStatus.IN_PROGRESS, null, 21L, APPROVAL_REQUEST_ID,
            "wf-9000", "act-100", "REFRESH_USER_SESSION", ApprovalRiskLevel.LOW,
            "policy-42", "1.0", "policy-dec-300", "auth-5678", DECIDED_AT, "evt-9", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }
}
