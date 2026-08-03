package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolved;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
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

/** SPEC-TW-010 domain-rules §2-4: {@code IN_PROGRESS -> RESOLVED} and its invariants. */
@Tag("unit")
class TicketResolutionTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-31T19:05:00Z");
    private static final Instant AUTO_CLOSE_DUE_AT = NOW.plusSeconds(604_800);
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "sam.support";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String SUMMARY = "Reinstalled the endpoint management profile and confirmed check-in.";

    private TicketResolved resolve() {
        return Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldResolveAnInProgressTicket() {
        TicketResolved event = resolve();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.resolutionCode()).isEqualTo(ResolutionCode.FIXED);
        assertThat(event.resolutionSummary()).isEqualTo(SUMMARY);
        assertThat(event.resolvedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.resolvedById()).isEqualTo(ACTOR_ID);
        assertThat(event.resolvedAt()).isEqualTo(NOW);
        assertThat(event.autoCloseDueAt()).isEqualTo(AUTO_CLOSE_DUE_AT);
        assertThat(event.transitionId()).isEqualTo("SM-010");
        assertThat(event.reasonCode()).isEqualTo("TICKET_RESOLVED");
        assertThat(event.aggregateVersion()).isEqualTo(18L);
    }

    @Test
    void shouldTrimTheResolutionSummary() {
        TicketResolved event = Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, "   " + SUMMARY + "   ", AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.resolutionSummary()).isEqualTo(SUMMARY);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanInProgress(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, currentStatus, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.RESOLVED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, null, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, null, 17L,
            ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullResolutionCode() {
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            null, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "too short", "123456789"})
    void shouldRejectABlankOrTooShortSummary(String summary) {
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, summary, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongSummary() {
        String tooLong = "a".repeat(5001);
        assertThatThrownBy(() -> Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, tooLong, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfTenAndFiveThousandCharacters() {
        String min = "a".repeat(10);
        String max = "a".repeat(5000);

        assertThat(Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, min, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).resolutionSummary()).hasSize(10);

        assertThat(Ticket.resolve(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            ResolutionCode.FIXED, max, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).resolutionSummary()).hasSize(5000);
    }
}
