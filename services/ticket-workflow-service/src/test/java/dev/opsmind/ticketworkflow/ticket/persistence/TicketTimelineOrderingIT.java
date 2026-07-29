package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §12: default ordering is {@code occurredAt ASC, itemTypeRank ASC, itemId ASC}, regardless of insertion order. */
@Tag("integration")
class TicketTimelineOrderingIT extends AbstractTicketTimelineIT {

    @Test
    void shouldOrderItemsByOccurredAtAscendingRegardlessOfInsertionOrder() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);

        // Seeded out of chronological order on purpose.
        seedPublicRequesterMessage(ticketId, "third message", DEFAULT_CREATED_AT.plusSeconds(300));
        UUID historyId = seedStatusHistory(ticketId, "NEW", "TRIAGING", DEFAULT_CREATED_AT.plusSeconds(60), 1);
        seedPublicSupportMessage(ticketId, "second message", DEFAULT_CREATED_AT.plusSeconds(180));

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        assertThat(items).hasSize(4);
        assertThat(items).extracting(TicketTimelineItem::occurredAt).isSorted();
        assertThat(items.get(0).itemId()).isEqualTo("TICKET_CREATED:" + ticketId);
        assertThat(items.get(1).itemId()).isEqualTo("STATUS_HISTORY:" + historyId);
        assertThat(items.get(1).occurredAt()).isEqualTo(DEFAULT_CREATED_AT.plusSeconds(60));
        assertThat(items.get(2).content()).isEqualTo("second message");
        assertThat(items.get(3).content()).isEqualTo("third message");
    }

    @Test
    void shouldExposeStableOrderingAcrossPagesWithoutDuplicates() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        for (int i = 1; i <= 5; i++) {
            seedPublicRequesterMessage(ticketId, "message " + i, DEFAULT_CREATED_AT.plusSeconds(60L * i));
        }
        Instant snapshotAt = DEFAULT_CREATED_AT.plusSeconds(3600);

        List<TicketTimelineItem> page1 = timelineQueryPort.queryTimeline(TicketId.of(ticketId), false, snapshotAt, null, 3);
        assertThat(page1).hasSize(3);

        TicketTimelineItem last = page1.get(page1.size() - 1);
        List<TicketTimelineItem> page2 = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, snapshotAt,
            new dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineKeysetPosition(
                last.occurredAt(), last.itemType().itemTypeRank(), last.itemId()
            ),
            100
        );

        List<String> page1Ids = page1.stream().map(TicketTimelineItem::itemId).toList();
        List<String> page2Ids = page2.stream().map(TicketTimelineItem::itemId).toList();
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1Ids);
        assertThat(page1Ids.size() + page2Ids.size()).isEqualTo(6);
    }
}
