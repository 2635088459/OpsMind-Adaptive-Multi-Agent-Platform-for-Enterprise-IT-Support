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
 * SPEC-TW-006 §12: when multiple items share the exact same {@code
 * occurredAt}, {@code itemTypeRank} breaks the tie first (TICKET_CREATED
 * before STATUS_CHANGED before message items), then {@code itemId}
 * lexicographically for items of the same rank.
 */
@Tag("integration")
class TicketTimelineEqualTimestampTieBreakerIT extends AbstractTicketTimelineIT {

    @Test
    void shouldOrderEqualTimestampItemsByItemTypeRankThenItemId() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);

        // Every source shares the exact same occurredAt as Ticket creation.
        UUID historyId = seedStatusHistory(ticketId, "NEW", "TRIAGING", DEFAULT_CREATED_AT, 1);
        UUID requesterMessageId = seedPublicRequesterMessage(ticketId, "requester message", DEFAULT_CREATED_AT);
        UUID supportMessageId = seedPublicSupportMessage(ticketId, "support message", DEFAULT_CREATED_AT);

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        assertThat(items).hasSize(4);
        assertThat(items).allMatch(item -> item.occurredAt().equals(DEFAULT_CREATED_AT));
        assertThat(items.get(0).itemType()).isEqualTo(TicketTimelineItemType.TICKET_CREATED);
        assertThat(items.get(0).itemId()).isEqualTo("TICKET_CREATED:" + ticketId);
        assertThat(items.get(1).itemType()).isEqualTo(TicketTimelineItemType.STATUS_CHANGED);
        assertThat(items.get(1).itemId()).isEqualTo("STATUS_HISTORY:" + historyId);

        // Both are PUBLIC_REQUESTER_MESSAGE/PUBLIC_SUPPORT_MESSAGE with different item-type ranks
        // (2 vs 3), so their relative order is rank-determined, not itemId-determined here.
        assertThat(items.get(2).itemType()).isEqualTo(TicketTimelineItemType.PUBLIC_REQUESTER_MESSAGE);
        assertThat(items.get(2).itemId()).isEqualTo("MESSAGE:" + requesterMessageId);
        assertThat(items.get(3).itemType()).isEqualTo(TicketTimelineItemType.PUBLIC_SUPPORT_MESSAGE);
        assertThat(items.get(3).itemId()).isEqualTo("MESSAGE:" + supportMessageId);
    }

    @Test
    void shouldOrderEqualRankEqualTimestampItemsByItemIdLexicographically() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);

        UUID first = seedPublicRequesterMessage(ticketId, "message A", DEFAULT_CREATED_AT.plusSeconds(60));
        UUID second = seedPublicRequesterMessage(ticketId, "message B", DEFAULT_CREATED_AT.plusSeconds(60));

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        List<TicketTimelineItem> sameInstant = items.stream()
            .filter(item -> item.occurredAt().equals(DEFAULT_CREATED_AT.plusSeconds(60)))
            .toList();
        assertThat(sameInstant).hasSize(2);

        // The keyset/order-by compares itemId as text (§10), which is lexicographic
        // string comparison on the UUID's canonical form — not UUID.compareTo()'s
        // signed numeric comparison, which can disagree with it.
        String firstItemId = "MESSAGE:" + first;
        String secondItemId = "MESSAGE:" + second;
        List<String> expectedOrder = firstItemId.compareTo(secondItemId) < 0
            ? List.of(firstItemId, secondItemId)
            : List.of(secondItemId, firstItemId);
        assertThat(sameInstant.get(0).itemId()).isEqualTo(expectedOrder.get(0));
        assertThat(sameInstant.get(1).itemId()).isEqualTo(expectedOrder.get(1));
    }
}
