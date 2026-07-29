package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §13: items created after a cursor session's {@code
 * snapshotAt} never enter a later page of that same session, but a fresh
 * (uncursored) query with a later snapshot does see them.
 *
 * <p>Exercises the query port directly with an explicit {@code snapshotAt}
 * rather than round-tripping through HTTP: the real wall clock backing
 * {@code ClockPort} cannot be pinned from an integration test (no test
 * override exists, unlike {@code SensitiveReadAuditPort}'s failure-injection
 * precedent), so controlling the snapshot boundary directly is the only way
 * to deterministically prove both halves of this behavior without a flaky,
 * timing-dependent sleep.
 */
@Tag("integration")
class TicketTimelineNewItemAfterSnapshotIT extends AbstractTicketTimelineIT {

    @Test
    void newItemAfterTheFixedSnapshotShouldNotEnterALaterPageOfTheSameSession() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "message 1", DEFAULT_CREATED_AT.plusSeconds(60));
        Instant snapshotAt = DEFAULT_CREATED_AT.plusSeconds(600);

        List<TicketTimelineItem> firstPage = timelineQueryPort.queryTimeline(TicketId.of(ticketId), false, snapshotAt, null, 100);
        assertThat(firstPage).hasSize(2);
        TicketTimelineItem last = firstPage.get(firstPage.size() - 1);
        TicketTimelineKeysetPosition cursorPosition = new TicketTimelineKeysetPosition(last.occurredAt(), last.itemType().itemTypeRank(), last.itemId());

        // Added after the first page's fixed snapshot boundary.
        seedPublicSupportMessage(ticketId, "new message after snapshot", DEFAULT_CREATED_AT.plusSeconds(900));

        List<TicketTimelineItem> secondPageOfOldSession = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, snapshotAt, cursorPosition, 100
        );

        assertThat(secondPageOfOldSession).isEmpty();
        assertThat(secondPageOfOldSession).noneMatch(item -> "new message after snapshot".equals(item.content()));
    }

    @Test
    void refreshedQueryWithANewerSnapshotShouldSeeTheNewItem() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "message 1", DEFAULT_CREATED_AT.plusSeconds(60));
        Instant oldSnapshotAt = DEFAULT_CREATED_AT.plusSeconds(600);

        List<TicketTimelineItem> firstPage = timelineQueryPort.queryTimeline(TicketId.of(ticketId), false, oldSnapshotAt, null, 100);
        assertThat(firstPage).hasSize(2);

        seedPublicSupportMessage(ticketId, "new message after snapshot", DEFAULT_CREATED_AT.plusSeconds(900));
        Instant refreshedSnapshotAt = DEFAULT_CREATED_AT.plusSeconds(1200);

        List<TicketTimelineItem> refreshed = timelineQueryPort.queryTimeline(TicketId.of(ticketId), false, refreshedSnapshotAt, null, 100);

        assertThat(refreshed).hasSize(3);
        assertThat(refreshed).anyMatch(item -> "new message after snapshot".equals(item.content()));
    }
}
