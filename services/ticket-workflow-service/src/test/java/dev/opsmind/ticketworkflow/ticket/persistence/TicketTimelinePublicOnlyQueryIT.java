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

/**
 * SPEC-TW-006 §17, §18: with {@code includeInternal = false}, the SQL
 * predicate itself excludes {@code INTERNAL_SUPPORT_NOTE} rows — an
 * Employee or Support-public query never reads an internal row at all, even
 * when internal notes exist in the database.
 */
@Tag("integration")
class TicketTimelinePublicOnlyQueryIT extends AbstractTicketTimelineIT {

    @Test
    void shouldNeverReturnInternalNotesWhenIncludeInternalIsFalse() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "public message", DEFAULT_CREATED_AT.plusSeconds(60));
        seedInternalSupportNote(ticketId, "internal note", DEFAULT_CREATED_AT.plusSeconds(120));

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        assertThat(items).extracting(TicketTimelineItem::itemType).doesNotContain(TicketTimelineItemType.INTERNAL_SUPPORT_NOTE);
        assertThat(items).hasSize(2);
        assertThat(items).noneMatch(item -> "INTERNAL".equals(item.visibility()));
    }
}
