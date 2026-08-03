package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-013 domain-rules: {@code WAITING_FOR_USER -> IN_PROGRESS} via a requester reply. */
@Tag("unit")
class TicketUserReplyResumeTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-03T19:15:00Z");
    private static final String ACTOR_TYPE = "EMPLOYEE";
    private static final String ACTOR_ID = "alice";

    @Test
    void shouldResumeAWaitingForUserTicket() {
        TicketUserInputResumed event = Ticket.resumeFromUserReply(
            TICKET_ID, TicketStatus.WAITING_FOR_USER, 21L, REQUEST_ID, MESSAGE_ID, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.repliedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.repliedById()).isEqualTo(ACTOR_ID);
        assertThat(event.repliedAt()).isEqualTo(NOW);
        assertThat(event.transitionId()).isEqualTo("SM-015");
        assertThat(event.reasonCode()).isEqualTo("USER_REPLIED");
        assertThat(event.aggregateVersion()).isEqualTo(22L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"WAITING_FOR_USER"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanWaitingForUser(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.resumeFromUserReply(
            TICKET_ID, currentStatus, 21L, REQUEST_ID, MESSAGE_ID, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            });
    }

    @Test
    void shouldRejectANullRequestId() {
        assertThatThrownBy(() -> Ticket.resumeFromUserReply(
            TICKET_ID, TicketStatus.WAITING_FOR_USER, 21L, null, MESSAGE_ID, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullMessageId() {
        assertThatThrownBy(() -> Ticket.resumeFromUserReply(
            TICKET_ID, TicketStatus.WAITING_FOR_USER, 21L, REQUEST_ID, null, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }
}
