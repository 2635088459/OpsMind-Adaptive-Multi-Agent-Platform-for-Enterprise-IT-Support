package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §17, §18: with {@code includeInternal = true}, internal notes are part of the UNION alongside every public source. */
@Tag("integration")
class TicketTimelineInternalQueryIT extends AbstractTicketTimelineIT {

    @Test
    void shouldReturnInternalNotesAlongsidePublicItemsWhenIncludeInternalIsTrue() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "public message", DEFAULT_CREATED_AT.plusSeconds(60));
        UUID internalNoteId = seedInternalSupportNote(ticketId, "internal note", DEFAULT_CREATED_AT.plusSeconds(120));

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), true, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        assertThat(items).hasSize(3);
        assertThat(items).extracting(TicketTimelineItem::itemType).contains(TicketTimelineItemType.INTERNAL_SUPPORT_NOTE);
        TicketTimelineItem internalNote = items.stream()
            .filter(item -> item.itemType() == TicketTimelineItemType.INTERNAL_SUPPORT_NOTE)
            .findFirst().orElseThrow();
        assertThat(internalNote.itemId()).isEqualTo("MESSAGE:" + internalNoteId);
        assertThat(internalNote.visibility()).isEqualTo("INTERNAL");
        assertThat(internalNote.content()).isEqualTo("internal note");
    }
}
