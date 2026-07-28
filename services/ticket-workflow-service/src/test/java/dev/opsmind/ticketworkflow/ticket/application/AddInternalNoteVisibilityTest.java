package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageAuthor;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-004 §6: internal notes must carry {@code INTERNAL} visibility
 * consistently through the domain object, the persisted record shape, and
 * the published event, so that every downstream reader (a future Employee
 * Timeline/message-list query, or a broker consumer) has a single reliable
 * signal to filter internal notes out of any Employee-facing view.
 */
@Tag("security")
class AddInternalNoteVisibilityTest {

    @Test
    void internalNoteShouldCarryInternalVisibilityThroughDomainAndEvent() {
        TicketMessage note = TicketMessage.create(
            TicketMessageId.of(UUID.randomUUID()),
            TicketId.of(UUID.randomUUID()),
            TicketMessageType.INTERNAL_SUPPORT_NOTE,
            new MessageAuthor("IT_SUPPORT", "support-100"),
            MessageContent.of("Identity verification is still required."),
            "cmd-1",
            Instant.parse("2026-07-25T18:30:00Z")
        );

        assertThat(note.visibility()).isEqualTo(MessageVisibility.INTERNAL);

        var events = note.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).visibility()).isEqualTo(MessageVisibility.INTERNAL);

        OutboxEventEntry outboxEntry = new TicketMessageAddedEventMapper().map(events.get(0), "trace-1", "corr-1", "cmd-1");
        assertThat(outboxEntry.payload()).containsEntry("visibility", "INTERNAL");
    }

    @Test
    void publicSupportMessageShouldCarryPublicVisibilityThroughDomainAndEvent() {
        TicketMessage message = TicketMessage.create(
            TicketMessageId.of(UUID.randomUUID()),
            TicketId.of(UUID.randomUUID()),
            TicketMessageType.PUBLIC_SUPPORT_MESSAGE,
            new MessageAuthor("IT_SUPPORT", "support-100"),
            MessageContent.of("The account has been unlocked. Please try again."),
            "cmd-2",
            Instant.parse("2026-07-25T18:30:00Z")
        );

        assertThat(message.visibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(message.pullDomainEvents().get(0).visibility()).isEqualTo(MessageVisibility.PUBLIC);
    }
}
