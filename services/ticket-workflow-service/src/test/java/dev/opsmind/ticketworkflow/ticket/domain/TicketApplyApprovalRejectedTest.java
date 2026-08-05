package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalRejectedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
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

/** SPEC-TW-016 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS} and its invariants. */
@Tag("unit")
class TicketApplyApprovalRejectedTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID APPROVAL_REQUEST_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final Instant REJECTED_AT = Instant.parse("2026-08-03T17:50:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketApprovalRejectedApplied apply() {
        return Ticket.applyApprovalRejected(
            TICKET_ID, TicketStatus.WAITING_FOR_APPROVAL, ASSIGNEE_ID, 21L, APPROVAL_REQUEST_ID, "appr-1234",
            "wf-9000", "act-100", "RESET_MFA", "sha256:approver", REJECTED_AT, "INSUFFICIENT_JUSTIFICATION", "evt-9", NOW
        );
    }

    @Test
    void shouldApplyApprovalRejectedOnAWaitingForApprovalTicket() {
        TicketApprovalRejectedApplied event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(event.approvalId()).isEqualTo("appr-1234");
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.actionType()).isEqualTo("RESET_MFA");
        assertThat(event.rejectedBy()).isEqualTo("sha256:approver");
        assertThat(event.rejectedAt()).isEqualTo(REJECTED_AT);
        assertThat(event.rejectionReason()).isEqualTo("INSUFFICIENT_JUSTIFICATION");
        assertThat(event.rejectedEventId()).isEqualTo("evt-9");
        assertThat(event.transitionId()).isEqualTo("SM-018");
        assertThat(event.reasonCode()).isEqualTo("APPROVAL_REJECTED");
        assertThat(event.aggregateVersion()).isEqualTo(22L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"WAITING_FOR_APPROVAL"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanWaitingForApproval(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyApprovalRejected(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 21L, APPROVAL_REQUEST_ID, "appr-1234",
            "wf-9000", "act-100", "RESET_MFA", "sha256:approver", REJECTED_AT, "INSUFFICIENT_JUSTIFICATION", "evt-9", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyApprovalRejected(
            TICKET_ID, TicketStatus.WAITING_FOR_APPROVAL, null, 21L, APPROVAL_REQUEST_ID, "appr-1234",
            "wf-9000", "act-100", "RESET_MFA", "sha256:approver", REJECTED_AT, "INSUFFICIENT_JUSTIFICATION", "evt-9", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldAllowANullRejectedBy() {
        TicketApprovalRejectedApplied event = Ticket.applyApprovalRejected(
            TICKET_ID, TicketStatus.WAITING_FOR_APPROVAL, ASSIGNEE_ID, 21L, APPROVAL_REQUEST_ID, "appr-1234",
            "wf-9000", "act-100", "RESET_MFA", null, REJECTED_AT, "INSUFFICIENT_JUSTIFICATION", "evt-9", NOW
        );

        assertThat(event.rejectedBy()).isNull();
    }
}
