package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolutionConfirmed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
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

/** SPEC-TW-026 domain-rules: {@code RESOLVED -> CLOSED} and its invariants. Mirrors {@code TicketCloseTest}'s (SPEC-TW-011) shape. */
@Tag("unit")
class TicketConfirmResolutionTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T20:10:00Z");
    private static final String ACTOR_TYPE = "EMPLOYEE";
    private static final String ACTOR_ID = "employee-123";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "Requester confirmed the issue is resolved and no further action is required.";

    private TicketResolutionConfirmed confirm() {
        return Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldConfirmAResolvedTicket() {
        TicketResolutionConfirmed event = confirm();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.confirmationReasonCode()).isEqualTo(ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED);
        assertThat(event.reason()).isEqualTo(REASON);
        assertThat(event.confirmedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.confirmedById()).isEqualTo(ACTOR_ID);
        assertThat(event.confirmedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-031");
        assertThat(event.reasonCode()).isEqualTo("RESOLUTION_CONFIRMED");
        assertThat(event.aggregateVersion()).isEqualTo(19L);
    }

    @Test
    void shouldTrimTheReason() {
        TicketResolutionConfirmed event = Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.reason()).isEqualTo(REASON);
    }

    @Test
    void shouldAcceptTheSupportConfirmedReasonCodeToo() {
        TicketResolutionConfirmed event = Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.SUPPORT_CONFIRMED, REASON, "IT_SUPPORT", "sam.support", NOW
        );

        assertThat(event.confirmationReasonCode()).isEqualTo(ResolutionConfirmationReasonCode.SUPPORT_CONFIRMED);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"RESOLVED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanResolved(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, currentStatus, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.CLOSED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, null, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, null, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullConfirmationReasonCode() {
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfThreeAndFiveHundredCharacters() {
        String min = "abc";
        String max = "a".repeat(500);

        assertThat(Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, min, ACTOR_TYPE, ACTOR_ID, NOW
        ).reason()).hasSize(3);

        assertThat(Ticket.confirmResolution(
            TICKET_ID, TicketStatus.RESOLVED, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 18L,
            ResolutionConfirmationReasonCode.REQUESTER_CONFIRMED, max, ACTOR_TYPE, ACTOR_ID, NOW
        ).reason()).hasSize(500);
    }
}
