package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalated;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
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

/** SPEC-TW-031 domain-rules: {@code mutable non-terminal state -> ESCALATED} and its invariants. */
class TicketEscalateTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final SupportQueueId QUEUE_ID = SupportQueueId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-07T23:00:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "lead.sam";
    private static final String TEAM_ID = "TEAM-A";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String WORKFLOW_ID = "wf-42";
    private static final String REASON = "Customer-facing outage with broad user impact.";

    private TicketEscalated escalate(TicketStatus currentStatus) {
        return Ticket.escalate(
            TICKET_ID, currentStatus, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID,
            EscalationReasonCode.USER_IMPACT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldEscalateAndPreserveWorkContext() {
        TicketEscalated event = escalate(TicketStatus.IN_PROGRESS);

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.teamId()).isEqualTo(TEAM_ID);
        assertThat(event.supportQueueId()).isEqualTo(QUEUE_ID);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(event.escalationReasonCode()).isEqualTo(EscalationReasonCode.USER_IMPACT);
        assertThat(event.escalationReason()).isEqualTo(REASON);
        assertThat(event.escalatedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.escalatedById()).isEqualTo(ACTOR_ID);
        assertThat(event.escalatedAt()).isEqualTo(NOW);
        assertThat(event.reasonCode()).isEqualTo("TICKET_ESCALATED");
        assertThat(event.aggregateVersion()).isEqualTo(8L);
    }

    @Test
    void shouldTrimTheEscalationReason() {
        TicketEscalated event = Ticket.escalate(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID,
            EscalationReasonCode.SLA_RISK, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.escalationReason()).isEqualTo(REASON);
    }

    @Test
    void shouldAllowAnUnownedUnqueuedTicketToEscalate() {
        TicketEscalated event = Ticket.escalate(
            TICKET_ID, TicketStatus.NEW, 0L, null, null, null, RESOLUTION_CYCLE_ID, null,
            EscalationReasonCode.POLICY_REQUIRED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.teamId()).isNull();
        assertThat(event.supportQueueId()).isNull();
        assertThat(event.assigneeId()).isNull();
        assertThat(event.workflowId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
        value = TicketStatus.class,
        names = {"NEW", "TRIAGED", "ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL", "EXECUTING", "VERIFYING", "RESOLVED"},
        mode = EnumSource.Mode.EXCLUDE
    )
    void shouldRejectEveryStatusOutsideTheEscalatableSet(TicketStatus currentStatus) {
        assertThatThrownBy(() -> escalate(currentStatus))
            .isInstanceOfSatisfying(InvalidTicketStateException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.allowedStatuses()).isEqualTo(Ticket.ESCALATABLE_STATUSES);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.escalate(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID,
            EscalationReasonCode.USER_IMPACT, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.escalate(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID,
            EscalationReasonCode.USER_IMPACT, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycleId() {
        assertThatThrownBy(() -> Ticket.escalate(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, null, WORKFLOW_ID,
            EscalationReasonCode.USER_IMPACT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingEscalationReasonCode() {
        assertThatThrownBy(() -> Ticket.escalate(
            TICKET_ID, TicketStatus.IN_PROGRESS, 7L, TEAM_ID, QUEUE_ID, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, WORKFLOW_ID,
            null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(
        value = TicketStatus.class,
        names = {"NEW", "TRIAGED", "ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL", "EXECUTING", "VERIFYING", "RESOLVED"}
    )
    void shouldAssignADistinctTransitionIdPerSourceStatus(TicketStatus currentStatus) {
        TicketEscalated event = escalate(currentStatus);

        assertThat(event.transitionId()).matches("SM-0(4[0-8])");
    }

    @Test
    void shouldAssignDistinctTransitionIdsAcrossAllSourceStatuses() {
        var transitionIds = java.util.Arrays.stream(new TicketStatus[]{
            TicketStatus.NEW, TicketStatus.TRIAGED, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.EXECUTING,
            TicketStatus.VERIFYING, TicketStatus.RESOLVED
        }).map(status -> escalate(status).transitionId()).distinct().count();

        assertThat(transitionIds).isEqualTo(9);
    }
}
