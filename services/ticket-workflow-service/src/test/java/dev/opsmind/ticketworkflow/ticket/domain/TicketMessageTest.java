package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageAuthor;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §9/§10: creation, derived visibility, and append-only domain events. */
@Tag("unit")
class TicketMessageTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:30:00Z");

    @Test
    void shouldCreateMessageWithVersionZeroAndDerivedVisibility() {
        TicketMessageId messageId = TicketMessageId.of(UUID.randomUUID());
        TicketId ticketId = TicketId.of(UUID.randomUUID());
        MessageAuthor author = new MessageAuthor("EMPLOYEE", "employee-123");
        MessageContent content = MessageContent.of("I restarted the VPN client, but the error still appears.");

        TicketMessage message = TicketMessage.create(
            messageId, ticketId, TicketMessageType.PUBLIC_REQUESTER_MESSAGE, author, content, "cmd-1", NOW
        );

        assertThat(message.id()).isEqualTo(messageId);
        assertThat(message.ticketId()).isEqualTo(ticketId);
        assertThat(message.messageType()).isEqualTo(TicketMessageType.PUBLIC_REQUESTER_MESSAGE);
        assertThat(message.visibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(message.author()).isEqualTo(author);
        assertThat(message.content()).isEqualTo(content);
        assertThat(message.createdAt()).isEqualTo(NOW);
        assertThat(message.version()).isZero();
    }

    @Test
    void shouldDeriveInternalVisibilityForInternalNotes() {
        TicketMessage message = TicketMessage.create(
            TicketMessageId.of(UUID.randomUUID()),
            TicketId.of(UUID.randomUUID()),
            TicketMessageType.INTERNAL_SUPPORT_NOTE,
            new MessageAuthor("IT_SUPPORT", "support-100"),
            MessageContent.of("Identity verification is still required."),
            "cmd-2",
            NOW
        );

        assertThat(message.visibility()).isEqualTo(MessageVisibility.INTERNAL);
    }

    @Test
    void shouldEmitExactlyOneTicketMessageAddedEventOnCreate() {
        TicketMessageId messageId = TicketMessageId.of(UUID.randomUUID());
        TicketId ticketId = TicketId.of(UUID.randomUUID());

        TicketMessage message = TicketMessage.create(
            messageId, ticketId, TicketMessageType.PUBLIC_SUPPORT_MESSAGE,
            new MessageAuthor("IT_SUPPORT", "support-100"),
            MessageContent.of("The account has been unlocked. Please try again."),
            "cmd-3", NOW
        );

        var events = message.pullDomainEvents();

        assertThat(events).hasSize(1);
        TicketMessageAdded event = events.get(0);
        assertThat(event.messageId()).isEqualTo(messageId);
        assertThat(event.ticketId()).isEqualTo(ticketId);
        assertThat(event.messageType()).isEqualTo(TicketMessageType.PUBLIC_SUPPORT_MESSAGE);
        assertThat(event.visibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(event.authorType()).isEqualTo("IT_SUPPORT");
        assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @Test
    void pullDomainEventsShouldClearAfterFirstPull() {
        TicketMessage message = TicketMessage.create(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(UUID.randomUUID()),
            TicketMessageType.PUBLIC_REQUESTER_MESSAGE,
            new MessageAuthor("EMPLOYEE", "employee-123"),
            MessageContent.of("hello"), "cmd-4", NOW
        );

        message.pullDomainEvents();

        assertThat(message.pullDomainEvents()).isEmpty();
    }
}
