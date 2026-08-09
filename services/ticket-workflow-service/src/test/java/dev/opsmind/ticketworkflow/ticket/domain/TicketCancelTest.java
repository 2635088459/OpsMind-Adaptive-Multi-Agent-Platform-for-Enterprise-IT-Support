package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCancelled;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-029 domain-rules: {@code non-terminal mutable state -> CANCELLED} and its invariants. Mirrors {@code TicketReopenTest}'s (SPEC-TW-011) multi-source-status shape. */
@Tag("unit")
class TicketCancelTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-07T22:00:00Z");
    private static final String ACTOR_TYPE = "EMPLOYEE";
    private static final String ACTOR_ID = "employee-123";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String REASON = "The requester no longer needs this request.";

    private TicketCancelled cancel(TicketStatus currentStatus, String assigneeId) {
        return Ticket.cancel(
            TICKET_ID, currentStatus, assigneeId, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @ParameterizedTest
    @CsvSource({
        "NEW,SM-033",
        "IN_PROGRESS,SM-034",
        "WAITING_FOR_USER,SM-035",
        "WAITING_FOR_APPROVAL,SM-036",
        "VERIFYING,SM-037",
        "RESOLVED,SM-038"
    })
    void shouldCancelFromEveryCancellableStatus(TicketStatus currentStatus, String expectedTransitionId) {
        TicketCancelled event = cancel(currentStatus, ASSIGNEE_ID);

        assertThat(event.previousStatus()).isEqualTo(currentStatus);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.cancelReasonCode()).isEqualTo(CancelReasonCode.NO_LONGER_NEEDED);
        assertThat(event.cancelReason()).isEqualTo(REASON);
        assertThat(event.cancelledByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.cancelledById()).isEqualTo(ACTOR_ID);
        assertThat(event.cancelledAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo(expectedTransitionId);
        assertThat(event.reasonCode()).isEqualTo("TICKET_CANCELLED");
        assertThat(event.aggregateVersion()).isEqualTo(6L);
    }

    @Test
    void shouldAllowAnUnassignedTicketToBeCancelled() {
        TicketCancelled event = cancel(TicketStatus.NEW, null);

        assertThat(event.assigneeId()).isNull();
        assertThat(event.newStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void shouldTrimTheCancelReason() {
        TicketCancelled event = Ticket.cancel(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, "   " + REASON + "   ", ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.cancelReason()).isEqualTo(REASON);
    }

    @ParameterizedTest
    @EnumSource(
        value = TicketStatus.class,
        names = {"NEW", "IN_PROGRESS", "WAITING_FOR_USER", "WAITING_FOR_APPROVAL", "VERIFYING", "RESOLVED"},
        mode = EnumSource.Mode.EXCLUDE
    )
    void shouldRejectEveryStatusOutsideTheCancellableSet(TicketStatus currentStatus) {
        assertThatThrownBy(() -> cancel(currentStatus, ASSIGNEE_ID))
            .isInstanceOfSatisfying(InvalidTicketStateException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.allowedStatuses()).isEqualTo(Ticket.CANCELLABLE_STATUSES);
            });
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, null, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullCancelReasonCode() {
        assertThatThrownBy(() -> Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            null, REASON, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab"})
    void shouldRejectABlankOrTooShortReason(String reason) {
        assertThatThrownBy(() -> Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, reason, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongReason() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, tooLong, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfThreeAndFiveHundredCharacters() {
        String min = "abc";
        String max = "a".repeat(500);

        assertThat(Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, min, ACTOR_TYPE, ACTOR_ID, NOW
        ).cancelReason()).hasSize(3);

        assertThat(Ticket.cancel(
            TICKET_ID, TicketStatus.NEW, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 5L,
            CancelReasonCode.NO_LONGER_NEEDED, max, ACTOR_TYPE, ACTOR_ID, NOW
        ).cancelReason()).hasSize(500);
    }
}
