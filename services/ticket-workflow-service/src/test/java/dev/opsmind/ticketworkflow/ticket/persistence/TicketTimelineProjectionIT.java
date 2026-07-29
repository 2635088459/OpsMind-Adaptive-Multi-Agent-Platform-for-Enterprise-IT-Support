package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §18: the {@code UNION ALL} projection correctly shapes every
 * Timeline source — Ticket creation, status history, and messages — into
 * one chronological stream. Queries the port directly (not HTTP) to isolate
 * SQL projection correctness from authorization and view mapping.
 */
@Tag("integration")
class TicketTimelineProjectionIT extends AbstractTicketTimelineIT {

    @Test
    void shouldProjectAllTimelineSourcesWithCorrectShape() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        UUID historyId = seedStatusHistory(ticketId, "NEW", "TRIAGING", DEFAULT_CREATED_AT.plusSeconds(60), 1);
        UUID requesterMessageId = seedPublicRequesterMessage(ticketId, "I still cannot sign in.", DEFAULT_CREATED_AT.plusSeconds(120));
        UUID supportMessageId = seedPublicSupportMessage(ticketId, "Looking into this now.", DEFAULT_CREATED_AT.plusSeconds(180));
        UUID internalNoteId = seedInternalSupportNote(ticketId, "Escalating to Duo team.", DEFAULT_CREATED_AT.plusSeconds(240));

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), true, DEFAULT_CREATED_AT.plusSeconds(3600), null, 100
        );

        assertThat(items).hasSize(5);

        TicketTimelineItem created = items.get(0);
        assertThat(created.itemId()).isEqualTo("TICKET_CREATED:" + ticketId);
        assertThat(created.itemType()).isEqualTo(TicketTimelineItemType.TICKET_CREATED);
        assertThat(created.visibility()).isEqualTo("PUBLIC");
        assertThat(created.occurredAt()).isEqualTo(DEFAULT_CREATED_AT);
        assertThat(created.actorType()).isEqualTo("EMPLOYEE");
        assertThat(created.actorId()).isEqualTo(DEFAULT_REQUESTER);

        TicketTimelineItem statusChanged = items.get(1);
        assertThat(statusChanged.itemId()).isEqualTo("STATUS_HISTORY:" + historyId);
        assertThat(statusChanged.itemType()).isEqualTo(TicketTimelineItemType.STATUS_CHANGED);
        assertThat(statusChanged.fromStatus()).isEqualTo("NEW");
        assertThat(statusChanged.toStatus()).isEqualTo("TRIAGING");
        assertThat(statusChanged.relatedVersion()).isEqualTo(1L);

        TicketTimelineItem requesterMessage = items.get(2);
        assertThat(requesterMessage.itemId()).isEqualTo("MESSAGE:" + requesterMessageId);
        assertThat(requesterMessage.itemType()).isEqualTo(TicketTimelineItemType.PUBLIC_REQUESTER_MESSAGE);
        assertThat(requesterMessage.content()).isEqualTo("I still cannot sign in.");

        TicketTimelineItem supportMessage = items.get(3);
        assertThat(supportMessage.itemId()).isEqualTo("MESSAGE:" + supportMessageId);
        assertThat(supportMessage.itemType()).isEqualTo(TicketTimelineItemType.PUBLIC_SUPPORT_MESSAGE);

        TicketTimelineItem internalNote = items.get(4);
        assertThat(internalNote.itemId()).isEqualTo("MESSAGE:" + internalNoteId);
        assertThat(internalNote.itemType()).isEqualTo(TicketTimelineItemType.INTERNAL_SUPPORT_NOTE);
        assertThat(internalNote.visibility()).isEqualTo("INTERNAL");
        assertThat(internalNote.content()).isEqualTo("Escalating to Duo team.");
    }

    @Test
    void shouldReadLimitPlusOneRowsToDetermineHasMore() {
        UUID ticketId = seedTicket();
        for (int i = 0; i < 3; i++) {
            seedPublicRequesterMessage(ticketId, "message " + i, DEFAULT_CREATED_AT.plusSeconds(60L * (i + 1)));
        }

        List<TicketTimelineItem> items = timelineQueryPort.queryTimeline(
            TicketId.of(ticketId), false, Instant.now(), null, 2
        );

        assertThat(items).hasSize(2);
    }
}
