package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketClosed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
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

/** SPEC-TW-011 domain-rules §1-2: {@code RESOLVED -> CLOSED} and its invariants. */
@Tag("unit")
class TicketCloseTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-31T20:10:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "sam.support";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Requester confirmed the issue is resolved and no further action is required.";

    private TicketClosed close() {
        return Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldCloseAResolvedTicket() {
        TicketClosed event = close();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.closeReasonCode()).isEqualTo(CloseReasonCode.REQUESTER_CONFIRMED);
        assertThat(event.closeReason()).isEqualTo(REASON);
        assertThat(event.closedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.closedById()).isEqualTo(ACTOR_ID);
        assertThat(event.closedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-011");
        assertThat(event.reasonCode()).isEqualTo("TICKET_CLOSED");
        assertThat(event.aggregateVersion()).isEqualTo(19L);
    }

    @Test
    void shouldTrimTheCloseReason() {
        TicketClosed event = Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.closeReason()).isEqualTo(REASON);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"RESOLVED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanResolved(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, currentStatus, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.CLOSED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, null, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, null, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullCloseReasonCode() {
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfThreeAndFiveHundredCharacters() {
        String min = "abc";
        String max = "a".repeat(500);

        assertThat(Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, min, ACTOR_TYPE, ACTOR_ID, NOW
        ).closeReason()).hasSize(3);

        assertThat(Ticket.close(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            CloseReasonCode.REQUESTER_CONFIRMED, max, ACTOR_TYPE, ACTOR_ID, NOW
        ).closeReason()).hasSize(500);
    }
}
