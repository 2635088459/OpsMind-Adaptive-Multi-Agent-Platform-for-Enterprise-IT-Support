package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalWaitStarted;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-014 domain-rules §1-2: {@code IN_PROGRESS -> WAITING_FOR_APPROVAL} and its invariants. */
@Tag("unit")
class TicketRequestApprovalTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID APPROVAL_REQUEST_ID = UUID.randomUUID();
    private static final String APPROVAL_ID = "appr-1234";
    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "sam.support";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final Map<String, Object> RISK_CONTEXT = Map.of("targetSystem", "identity", "requesterImpact", "account_access");

    private TicketApprovalWaitStarted request() {
        return Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT,
            "MFA reset requires approval before execution.", ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldRequestApprovalOnAnInProgressTicket() {
        TicketApprovalWaitStarted event = request();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(event.approvalId()).isEqualTo(APPROVAL_ID);
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.actionType()).isEqualTo("RESET_MFA");
        assertThat(event.riskLevel()).isEqualTo(ApprovalRiskLevel.HIGH);
        assertThat(event.riskContext()).isEqualTo(RISK_CONTEXT);
        assertThat(event.reason()).isEqualTo("MFA reset requires approval before execution.");
        assertThat(event.requestedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.requestedById()).isEqualTo(ACTOR_ID);
        assertThat(event.requestedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-016");
        assertThat(event.reasonCode()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(event.aggregateVersion()).isEqualTo(21L);
    }

    @Test
    void shouldTrimWorkflowIdActionIdActionTypeAndReason() {
        TicketApprovalWaitStarted event = Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "  wf-9000  ", "  act-100  ", "  RESET_MFA  ", ApprovalRiskLevel.HIGH, RISK_CONTEXT,
            "  reason text  ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.actionType()).isEqualTo("RESET_MFA");
        assertThat(event.reason()).isEqualTo("reason text");
    }

    @Test
    void shouldAllowANullReason() {
        TicketApprovalWaitStarted event = Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, null, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.reason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanInProgress(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, null, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectABlankWorkflowId() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "   ", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectABlankActionId() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "   ", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectABlankActionType() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "   ", ApprovalRiskLevel.HIGH, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAnEmptyRiskContext() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, Map.of(), "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectANullRiskContext() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, null, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectANullRiskLevel() {
        assertThatThrownBy(() -> Ticket.requestApproval(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, APPROVAL_REQUEST_ID, APPROVAL_ID,
            "wf-9000", "act-100", "RESET_MFA", null, RISK_CONTEXT, "reason", ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }
}
