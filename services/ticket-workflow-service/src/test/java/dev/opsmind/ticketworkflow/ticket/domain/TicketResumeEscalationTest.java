package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalationResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-032 domain-rules: {@code ESCALATED -> IN_PROGRESS} and its invariants. */
class TicketResumeEscalationTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final SupportQueueId QUEUE_ID = SupportQueueId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-08T23:00:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "lead.sam";
    private static final String TEAM_ID = "TEAM-A";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Root cause identified and mitigated; resuming active work.";

    private TicketEscalationResumed resume(TicketStatus currentStatus, OwnershipStatus ownershipStatus) {
        return Ticket.resumeEscalation(
            TICKET_ID, currentStatus, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, REASON, ownershipStatus, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldResumeAnEscalatedTicketIntoInProgress() {
        TicketEscalationResumed event = resume(TicketStatus.ESCALATED, OwnershipStatus.ACTIVE);

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.teamId()).isEqualTo(TEAM_ID);
        assertThat(event.supportQueueId()).isEqualTo(QUEUE_ID);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.resumeReasonCode()).isEqualTo(EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED);
        assertThat(event.resumeReason()).isEqualTo(REASON);
        assertThat(event.resumedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.resumedById()).isEqualTo(ACTOR_ID);
        assertThat(event.resumedAt()).isEqualTo(NOW);
        assertThat(event.ownershipStatus()).isEqualTo(OwnershipStatus.ACTIVE);
        assertThat(event.transitionId()).isEqualTo("SM-049");
        assertThat(event.reasonCode()).isEqualTo("TICKET_ESCALATION_RESUMED");
        assertThat(event.aggregateVersion()).isEqualTo(8L);
    }

    @Test
    void shouldReportAnUnassignedOwnershipStatusWithoutBlockingTheResume() {
        TicketEscalationResumed event = resume(TicketStatus.ESCALATED, OwnershipStatus.UNASSIGNED);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.ownershipStatus()).isEqualTo(OwnershipStatus.UNASSIGNED);
    }

    @Test
    void shouldReportAnInactiveAssigneeWithoutBlockingTheResume() {
        TicketEscalationResumed event = resume(TicketStatus.ESCALATED, OwnershipStatus.ASSIGNEE_INACTIVE);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.ownershipStatus()).isEqualTo(OwnershipStatus.ASSIGNEE_INACTIVE);
    }

    @Test
    void shouldTrimTheResumeReason() {
        TicketEscalationResumed event = Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.MITIGATION_APPLIED, "   " + REASON + "   ", OwnershipStatus.ACTIVE, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.resumeReason()).isEqualTo(REASON);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"ESCALATED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanEscalated(TicketStatus currentStatus) {
        assertThatThrownBy(() -> resume(currentStatus, OwnershipStatus.ACTIVE))
            .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.ESCALATED);
            });
    }

    @Test
    void shouldAllowAnUnownedTicketToResume() {
        TicketEscalationResumed event = Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 0L, null, null, null, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.ESCALATION_NOT_REQUIRED, REASON, OwnershipStatus.UNASSIGNED, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.teamId()).isNull();
        assertThat(event.supportQueueId()).isNull();
        assertThat(event.assigneeId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, reason, OwnershipStatus.ACTIVE, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, tooLong, OwnershipStatus.ACTIVE, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycleId() {
        assertThatThrownBy(() -> Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, null,
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, REASON, OwnershipStatus.ACTIVE, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingResumeReasonCode() {
        assertThatThrownBy(() -> Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            null, REASON, OwnershipStatus.ACTIVE, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingOwnershipStatus() {
        assertThatThrownBy(() -> Ticket.resumeEscalation(
            TICKET_ID, TicketStatus.ESCALATED, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID,
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED, REASON, null, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }
}
