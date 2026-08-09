package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoClosed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AutoCloseNotYetDueException;
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

/** SPEC-TW-027 domain-rules: {@code RESOLVED -> CLOSED} and its invariants. Mirrors {@code TicketCloseTest}'s (SPEC-TW-011) shape. */
@Tag("unit")
class TicketAutoCloseTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant AUTO_CLOSE_DUE_AT = Instant.parse("2026-08-06T20:10:00Z");
    private static final Instant NOW = AUTO_CLOSE_DUE_AT.plusSeconds(60);
    private static final String ACTOR_TYPE = "SERVICE";
    private static final String ACTOR_ID = "auto-close-scheduler";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Auto-close policy window elapsed without further activity.";

    private TicketAutoClosed autoClose() {
        return Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldAutoCloseAResolvedTicketPastItsDueDate() {
        TicketAutoClosed event = autoClose();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.closeReasonCode()).isEqualTo(CloseReasonCode.AUTO_CLOSE_TIMEOUT);
        assertThat(event.reason()).isEqualTo(REASON);
        assertThat(event.closedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.closedById()).isEqualTo(ACTOR_ID);
        assertThat(event.closedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-032");
        assertThat(event.reasonCode()).isEqualTo("TICKET_AUTO_CLOSED");
        assertThat(event.aggregateVersion()).isEqualTo(19L);
    }

    @Test
    void shouldAllowExactlyAtTheDueDate() {
        TicketAutoClosed event = Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, AUTO_CLOSE_DUE_AT
        );

        assertThat(event.closedAt()).isEqualTo(AUTO_CLOSE_DUE_AT);
    }

    @Test
    void shouldRejectBeforeTheDueDate() {
        Instant tooEarly = AUTO_CLOSE_DUE_AT.minusSeconds(1);

        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, tooEarly
        )).isInstanceOf(AutoCloseNotYetDueException.class);
    }

    @Test
    void shouldTrimTheReason() {
        TicketAutoClosed event = Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.reason()).isEqualTo(REASON);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"RESOLVED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanResolved(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, currentStatus, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.CLOSED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, null, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, null, 18L,
            AUTO_CLOSE_DUE_AT, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingAutoCloseDueAt() {
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfThreeAndFiveHundredCharacters() {
        String min = "abc";
        String max = "a".repeat(500);

        assertThat(Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, min, ACTOR_TYPE, ACTOR_ID, NOW
        ).reason()).hasSize(3);

        assertThat(Ticket.autoClose(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            AUTO_CLOSE_DUE_AT, max, ACTOR_TYPE, ACTOR_ID, NOW
        ).reason()).hasSize(500);
    }
}
