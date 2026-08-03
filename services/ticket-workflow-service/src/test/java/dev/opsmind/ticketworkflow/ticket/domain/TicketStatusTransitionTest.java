package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class TicketStatusTransitionTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-07-31T18:35:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "support-100";
    private static final String ASSIGNEE_ID = "agent-1";
    private static final String REASON = "Starting endpoint investigation";

    @ParameterizedTest
    @CsvSource({
        "ASSIGNED,IN_PROGRESS,SM-005,WORK_STARTED",
        "IN_PROGRESS,WAITING_FOR_USER,SM-006,WAITING_FOR_USER",
        "IN_PROGRESS,WAITING_FOR_APPROVAL,SM-007,WAITING_FOR_APPROVAL",
        "WAITING_FOR_USER,IN_PROGRESS,SM-008,WORK_RESUMED",
        "WAITING_FOR_APPROVAL,IN_PROGRESS,SM-009,WORK_RESUMED"
    })
    void shouldAllowOnlyTheSpecTw009TransitionMatrix(
        TicketStatus currentStatus, TicketStatus targetStatus, String transitionId, String reasonCode
    ) {
        TicketStatusChanged event = Ticket.transitionStatus(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 13L, targetStatus, ACTOR_TYPE, ACTOR_ID, REASON,
            targetStatus == TicketStatus.WAITING_FOR_USER ? NOW : null,
            targetStatus == TicketStatus.WAITING_FOR_APPROVAL ? "approval-req-1" : null,
            NOW
        );

        assertThat(event.previousStatus()).isEqualTo(currentStatus);
        assertThat(event.newStatus()).isEqualTo(targetStatus);
        assertThat(event.transitionId()).isEqualTo(transitionId);
        assertThat(event.reasonCode()).isEqualTo(reasonCode);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.aggregateVersion()).isEqualTo(14L);
    }

    @Test
    void shouldDefaultWaitingForRequesterSinceToOccurredAt() {
        TicketStatusChanged event = Ticket.transitionStatus(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 13L, TicketStatus.WAITING_FOR_USER,
            ACTOR_TYPE, ACTOR_ID, REASON, null, null, NOW
        );

        assertThat(event.waitingForRequesterSince()).isEqualTo(NOW);
        assertThat(event.approvalReference()).isNull();
    }

    @Test
    void shouldStoreApprovalReferenceForWaitingForApproval() {
        TicketStatusChanged event = Ticket.transitionStatus(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 13L, TicketStatus.WAITING_FOR_APPROVAL,
            ACTOR_TYPE, ACTOR_ID, REASON, null, "approval-req-1", NOW
        );

        assertThat(event.approvalReference()).isEqualTo("approval-req-1");
        assertThat(event.waitingForRequesterSince()).isNull();
    }

    @Test
    void shouldRejectMissingAssignee() {
        assertThatThrownBy(() -> Ticket.transitionStatus(
            TICKET_ID, TicketStatus.ASSIGNED, null, 13L, TicketStatus.IN_PROGRESS,
            ACTOR_TYPE, ACTOR_ID, REASON, null, null, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "NEW,IN_PROGRESS",
        "TRIAGED,IN_PROGRESS",
        "ASSIGNED,WAITING_FOR_USER",
        "WAITING_FOR_USER,WAITING_FOR_APPROVAL",
        "IN_PROGRESS,RESOLVED",
        "CLOSED,IN_PROGRESS"
    })
    void shouldRejectInvalidTransitions(TicketStatus currentStatus, TicketStatus targetStatus) {
        assertThatThrownBy(() -> Ticket.transitionStatus(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 13L, targetStatus,
            ACTOR_TYPE, ACTOR_ID, REASON, null, null, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(targetStatus);
            });
    }
}
