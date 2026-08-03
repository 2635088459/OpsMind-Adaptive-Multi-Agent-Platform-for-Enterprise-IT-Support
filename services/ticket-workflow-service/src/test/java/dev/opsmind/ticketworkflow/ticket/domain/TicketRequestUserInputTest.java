package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-012 domain-rules §1-2: {@code IN_PROGRESS -> WAITING_FOR_USER} and its invariants. */
@Tag("unit")
class TicketRequestUserInputTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-05T18:00:00Z");
    private static final String ACTOR_TYPE = "IT_SUPPORT";
    private static final String ACTOR_ID = "sam.support";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String PROMPT = "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.";

    private TicketUserInputRequested request() {
        return Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, PROMPT,
            List.of("screenshot", "vpnStatus"), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldRequestUserInputOnAnInProgressTicket() {
        TicketUserInputRequested event = request();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.prompt()).isEqualTo(PROMPT);
        assertThat(event.requestedFields()).containsExactly("screenshot", "vpnStatus");
        assertThat(event.requestedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.requestedById()).isEqualTo(ACTOR_ID);
        assertThat(event.requestedAt()).isEqualTo(NOW);
        assertThat(event.waitingForRequesterSince()).isEqualTo(NOW);
        assertThat(event.resumeStatus()).isEqualTo("IN_PROGRESS");
        assertThat(event.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(event.transitionId()).isEqualTo("SM-014");
        assertThat(event.reasonCode()).isEqualTo("USER_INPUT_REQUIRED");
        assertThat(event.aggregateVersion()).isEqualTo(21L);
    }

    @Test
    void shouldTrimThePrompt() {
        TicketUserInputRequested event = Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, "   " + PROMPT + "   ",
            List.of(), null, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.prompt()).isEqualTo(PROMPT);
    }

    @Test
    void shouldDefaultNullRequestedFieldsToAnEmptyList() {
        TicketUserInputRequested event = Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, PROMPT, null, null, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.requestedFields()).isEmpty();
        assertThat(event.expiresAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanInProgress(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.requestUserInput(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 20L, REQUEST_ID, PROMPT, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, null, 20L, REQUEST_ID, PROMPT, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectANullPrompt() {
        assertThatThrownBy(() -> Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, null, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "too short"})
    void shouldRejectABlankOrTooShortPrompt(String prompt) {
        assertThatThrownBy(() -> Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, prompt, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongPrompt() {
        String tooLong = "a".repeat(2001);
        assertThatThrownBy(() -> Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, tooLong, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfTenAndTwoThousandCharacters() {
        String min = "a".repeat(10);
        String max = "a".repeat(2000);

        assertThat(Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, min, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).prompt()).hasSize(10);

        assertThat(Ticket.requestUserInput(
            TICKET_ID, TicketStatus.IN_PROGRESS, ASSIGNEE_ID, 20L, REQUEST_ID, max, List.of(), EXPIRES_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).prompt()).hasSize(2000);
    }
}
