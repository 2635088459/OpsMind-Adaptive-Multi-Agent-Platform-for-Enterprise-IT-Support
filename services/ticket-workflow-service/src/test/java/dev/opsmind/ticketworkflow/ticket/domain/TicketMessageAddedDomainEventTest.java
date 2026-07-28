package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-004 §9/§15: the event carries identity/type/visibility/author type/time only. */
@Tag("unit")
class TicketMessageAddedDomainEventTest {

    private static final TicketMessageId MESSAGE_ID = TicketMessageId.of(UUID.randomUUID());
    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-07-25T18:30:00Z");

    @Test
    void shouldConstructWithAllRequiredFields() {
        TicketMessageAdded event = new TicketMessageAdded(
            MESSAGE_ID, TICKET_ID, TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC, "EMPLOYEE", NOW
        );

        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.ticketId()).isEqualTo(TICKET_ID);
        assertThat(event.messageType()).isEqualTo(TicketMessageType.PUBLIC_REQUESTER_MESSAGE);
        assertThat(event.visibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(event.authorType()).isEqualTo("EMPLOYEE");
        assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectNullMessageId() {
        assertThatThrownBy(() -> new TicketMessageAdded(
            null, TICKET_ID, TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC, "EMPLOYEE", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() -> new TicketMessageAdded(
            MESSAGE_ID, TICKET_ID, TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC, "EMPLOYEE", null
        )).isInstanceOf(NullPointerException.class);
    }
}
