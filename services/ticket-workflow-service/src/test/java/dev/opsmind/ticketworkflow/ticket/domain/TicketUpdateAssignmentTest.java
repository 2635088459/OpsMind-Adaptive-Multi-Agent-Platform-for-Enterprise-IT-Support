package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssignmentUpdated;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssigneeRequiredForCurrentStatusException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssignmentRequiresAChangeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-030 domain-rules: {@code mutable non-terminal state -> same lifecycle state} and its invariants. */
@Tag("unit")
class TicketUpdateAssignmentTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final SupportQueueId CURRENT_QUEUE_ID = SupportQueueId.of(UUID.randomUUID());
    private static final SupportQueueId NEW_QUEUE_ID = SupportQueueId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-07T23:00:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "lead.sam";
    private static final String CURRENT_TEAM_ID = "TEAM-A";
    private static final String NEW_TEAM_ID = "TEAM-B";
    private static final String CURRENT_ASSIGNEE_ID = "sam.support";
    private static final String NEW_ASSIGNEE_ID = "alex.support";
    private static final String REASON = "Rebalancing queue load across teams.";

    private TicketAssignmentUpdated updateAssignment(
        TicketStatus currentStatus, String newTeamId, SupportQueueId newQueueId, String newAssigneeId
    ) {
        return Ticket.updateAssignment(
            TICKET_ID, currentStatus, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            newTeamId, newQueueId, newAssigneeId, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldUpdateTeamQueueAndAssigneeTogether() {
        TicketAssignmentUpdated event = updateAssignment(TicketStatus.IN_PROGRESS, NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID);

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.previousTeamId()).isEqualTo(CURRENT_TEAM_ID);
        assertThat(event.newTeamId()).isEqualTo(NEW_TEAM_ID);
        assertThat(event.previousSupportQueueId()).isEqualTo(CURRENT_QUEUE_ID);
        assertThat(event.newSupportQueueId()).isEqualTo(NEW_QUEUE_ID);
        assertThat(event.previousAssigneeId()).isEqualTo(CURRENT_ASSIGNEE_ID);
        assertThat(event.newAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        assertThat(event.reason()).isEqualTo(REASON);
        assertThat(event.updatedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.updatedById()).isEqualTo(ACTOR_ID);
        assertThat(event.updatedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-039");
        assertThat(event.reasonCode()).isEqualTo("TICKET_ASSIGNMENT_UPDATED");
        assertThat(event.aggregateVersion()).isEqualTo(8L);
    }

    @Test
    void shouldAllowClearingTheAssigneeWhileMovingQueues() {
        // NEW is outside STATUSES_REQUIRING_ASSIGNEE (unlike IN_PROGRESS,
        // whose V015 ck_tickets_work_states_have_assignee CHECK constraint
        // forbids a null current_support_user_id).
        TicketAssignmentUpdated event = updateAssignment(TicketStatus.NEW, NEW_TEAM_ID, NEW_QUEUE_ID, null);

        assertThat(event.newAssigneeId()).isNull();
        assertThat(event.newTeamId()).isEqualTo(NEW_TEAM_ID);
    }

    @Test
    void shouldRejectClearingTheAssigneeWhileTheStatusRequiresOne() {
        assertThatThrownBy(() -> updateAssignment(TicketStatus.IN_PROGRESS, NEW_TEAM_ID, NEW_QUEUE_ID, null))
            .isInstanceOf(AssigneeRequiredForCurrentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL", "RESOLVED"})
    void shouldRejectClearingTheAssigneeForEveryStatusThatRequiresOne(TicketStatus status) {
        assertThatThrownBy(() -> updateAssignment(status, NEW_TEAM_ID, NEW_QUEUE_ID, null))
            .isInstanceOf(AssigneeRequiredForCurrentStatusException.class);
    }

    @Test
    void shouldAllowChangingOnlyTheAssigneeWithinTheSameQueue() {
        TicketAssignmentUpdated event = updateAssignment(TicketStatus.IN_PROGRESS, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, NEW_ASSIGNEE_ID);

        assertThat(event.newTeamId()).isEqualTo(CURRENT_TEAM_ID);
        assertThat(event.newSupportQueueId()).isEqualTo(CURRENT_QUEUE_ID);
        assertThat(event.newAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
    }

    @Test
    void shouldTrimTheReason() {
        TicketAssignmentUpdated event = Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.reason()).isEqualTo(REASON);
    }

    @ParameterizedTest
    @EnumSource(
        value = TicketStatus.class,
        names = {
            "NEW", "TRIAGED", "ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER",
            "WAITING_FOR_APPROVAL", "EXECUTING", "VERIFYING", "RESOLVED", "ESCALATED", "FAILED"
        },
        mode = EnumSource.Mode.EXCLUDE
    )
    void shouldRejectEveryStatusOutsideTheAssignableSet(TicketStatus currentStatus) {
        assertThatThrownBy(() -> updateAssignment(currentStatus, NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID))
            .isInstanceOfSatisfying(InvalidTicketStateException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.allowedStatuses()).isEqualTo(Ticket.ASSIGNABLE_STATUSES);
            });
    }

    @Test
    void shouldRejectWhenNothingWouldActuallyChange() {
        assertThatThrownBy(() -> updateAssignment(TicketStatus.IN_PROGRESS, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID))
            .isInstanceOf(AssignmentRequiresAChangeException.class);
    }

    @Test
    void shouldRejectWhenNothingChangesAndBothAssigneesAreNull() {
        assertThatThrownBy(() -> Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, null,
            CURRENT_TEAM_ID, CURRENT_QUEUE_ID, null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(AssignmentRequiresAChangeException.class);
    }

    @Test
    void shouldRejectAMissingNewTeamId() {
        assertThatThrownBy(() -> Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            null, NEW_QUEUE_ID, NEW_ASSIGNEE_ID, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingNewSupportQueueId() {
        assertThatThrownBy(() -> Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            NEW_TEAM_ID, null, NEW_ASSIGNEE_ID, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.updateAssignment(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, CURRENT_TEAM_ID, CURRENT_QUEUE_ID, CURRENT_ASSIGNEE_ID,
            NEW_TEAM_ID, NEW_QUEUE_ID, NEW_ASSIGNEE_ID, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowAnUnassignedTicketToBeRouted() {
        TicketAssignmentUpdated event = Ticket.updateAssignment(
            TICKET_ID, TicketStatus.NEW, 0L, null, CURRENT_QUEUE_ID, null,
            NEW_TEAM_ID, NEW_QUEUE_ID, null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.previousTeamId()).isNull();
        assertThat(event.previousAssigneeId()).isNull();
        assertThat(event.newTeamId()).isEqualTo(NEW_TEAM_ID);
    }
}
