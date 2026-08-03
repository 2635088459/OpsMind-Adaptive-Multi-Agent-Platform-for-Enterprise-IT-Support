package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.ReassignmentRequiresDifferentAssigneeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketAlreadyAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Ticket#assign}/{@link Ticket#reassign}/{@link Ticket#unassign}
 * (SPEC-TW-008 §2-5). Mirrors {@code TicketTriageTest}'s structure: each
 * static method's success/rejection/null-guard scenarios are grouped in
 * their own {@code @Nested} class.
 */
@Tag("unit")
class TicketAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-07-31T18:30:00Z");
    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "support-100";
    private static final String REASON = "Primary endpoint support owner";

    @Nested
    class Assign {

        @Test
        void shouldAssignATriagedUnownedTicketAndIncrementVersionExactlyOnce() {
            TicketAssigned event = Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 7L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            );

            assertThat(event.ticketId()).isEqualTo(TICKET_ID);
            assertThat(event.previousStatus()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(event.assigneeId()).isEqualTo("agent-1");
            assertThat(event.actorType()).isEqualTo(ACTOR_TYPE);
            assertThat(event.actorId()).isEqualTo(ACTOR_ID);
            assertThat(event.reason()).isEqualTo(REASON);
            assertThat(event.aggregateVersion()).isEqualTo(8L);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        }

        @ParameterizedTest
        @EnumSource(value = TicketStatus.class, names = "TRIAGED", mode = EnumSource.Mode.EXCLUDE)
        void shouldRejectAssignFromEveryNonTriagedStatus(TicketStatus currentStatus) {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, currentStatus, null, 3L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            ))
                .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                    assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                    assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.TRIAGED);
                });
        }

        @Test
        void shouldRejectAssignWhenTheTicketAlreadyHasAnAssigneeEvenWhileTriaged() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, "existing-agent", 3L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(TicketAlreadyAssignedException.class);
        }

        @Test
        void shouldRejectNullTicketId() {
            assertThatThrownBy(() -> Ticket.assign(
                null, TicketStatus.TRIAGED, null, 0L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullCurrentStatus() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, null, null, 0L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullAssigneeId() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 0L, null, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorType() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 0L, "agent-1", null, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorId() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 0L, "agent-1", ACTOR_TYPE, null, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullReason() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 0L, "agent-1", ACTOR_TYPE, ACTOR_ID, null, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullOccurredAt() {
            assertThatThrownBy(() -> Ticket.assign(
                TICKET_ID, TicketStatus.TRIAGED, null, 0L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Reassign {

        @ParameterizedTest
        @EnumSource(value = TicketStatus.class, names = {"ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL"})
        void shouldReassignFromEveryReassignableStatusPreservingStatusAndIncrementingVersion(TicketStatus currentStatus) {
            TicketReassigned event = Ticket.reassign(
                TICKET_ID, currentStatus, "agent-1", 7L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            );

            assertThat(event.ticketId()).isEqualTo(TICKET_ID);
            assertThat(event.status()).isEqualTo(currentStatus);
            assertThat(event.previousAssigneeId()).isEqualTo("agent-1");
            assertThat(event.assigneeId()).isEqualTo("agent-2");
            assertThat(event.actorType()).isEqualTo(ACTOR_TYPE);
            assertThat(event.actorId()).isEqualTo(ACTOR_ID);
            assertThat(event.reason()).isEqualTo(REASON);
            assertThat(event.aggregateVersion()).isEqualTo(8L);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        }

        @Test
        void shouldRejectReassignWhenTheTicketHasNoCurrentAssignee() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, null, 3L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(TicketNotAssignedException.class);
        }

        @ParameterizedTest
        @EnumSource(value = TicketStatus.class, names = {"ASSIGNED", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL"}, mode = EnumSource.Mode.EXCLUDE)
        void shouldRejectReassignFromEveryStatusOutsideTheReassignableSet(TicketStatus currentStatus) {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, currentStatus, "agent-1", 3L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            ))
                .isInstanceOfSatisfying(InvalidTicketStateException.class, ex -> {
                    assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                    assertThat(ex.allowedStatuses()).isEqualTo(Ticket.REASSIGNABLE_STATUSES);
                });
        }

        @Test
        void shouldRejectReassignToTheSameCurrentAssignee() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 3L, "agent-1", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(ReassignmentRequiresDifferentAssigneeException.class);
        }

        @Test
        void shouldRejectNullTicketId() {
            assertThatThrownBy(() -> Ticket.reassign(
                null, TicketStatus.ASSIGNED, "agent-1", 0L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullCurrentStatus() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, null, "agent-1", 0L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullNewAssigneeId() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, null, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorType() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, "agent-2", null, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorId() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, "agent-2", ACTOR_TYPE, null, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullReason() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, "agent-2", ACTOR_TYPE, ACTOR_ID, null, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullOccurredAt() {
            assertThatThrownBy(() -> Ticket.reassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, "agent-2", ACTOR_TYPE, ACTOR_ID, REASON, null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Unassign {

        @Test
        void shouldUnassignAnAssignedTicketAndReturnItToTriaged() {
            TicketUnassigned event = Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 7L, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            );

            assertThat(event.ticketId()).isEqualTo(TICKET_ID);
            assertThat(event.previousStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.TRIAGED);
            assertThat(event.previousAssigneeId()).isEqualTo("agent-1");
            assertThat(event.actorType()).isEqualTo(ACTOR_TYPE);
            assertThat(event.actorId()).isEqualTo(ACTOR_ID);
            assertThat(event.reason()).isEqualTo(REASON);
            assertThat(event.aggregateVersion()).isEqualTo(8L);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        }

        @ParameterizedTest
        @EnumSource(value = TicketStatus.class, names = "ASSIGNED", mode = EnumSource.Mode.EXCLUDE)
        void shouldRejectUnassignFromEveryNonAssignedStatus(TicketStatus currentStatus) {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, currentStatus, "agent-1", 3L, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            ))
                .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                    assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                    assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.ASSIGNED);
                });
        }

        @Test
        void shouldRejectUnassignWhenTheTicketHasNoCurrentAssigneeEvenWhileAssigned() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, null, 3L, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(TicketNotAssignedException.class);
        }

        @Test
        void shouldRejectNullTicketId() {
            assertThatThrownBy(() -> Ticket.unassign(
                null, TicketStatus.ASSIGNED, "agent-1", 0L, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullCurrentStatus() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, null, "agent-1", 0L, ACTOR_TYPE, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorType() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, null, ACTOR_ID, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullActorId() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, ACTOR_TYPE, null, REASON, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullReason() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, ACTOR_TYPE, ACTOR_ID, null, NOW
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullOccurredAt() {
            assertThatThrownBy(() -> Ticket.unassign(
                TICKET_ID, TicketStatus.ASSIGNED, "agent-1", 0L, ACTOR_TYPE, ACTOR_ID, REASON, null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void reassignableStatusesShouldIncludeInProgressAfterSpecTw009() {
        assertThat(Ticket.REASSIGNABLE_STATUSES).isEqualTo(
            EnumSet.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL)
        );
        assertThat(Ticket.REASSIGNABLE_STATUSES).isEqualTo(
            Set.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL)
        );
    }
}
