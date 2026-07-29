package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §27: the Timeline projection's per-source {@code ticket_id}
 * predicate uses {@code ix_ticket_status_history_ticket_time} and {@code
 * ix_ticket_messages_ticket_created} rather than a full scan, mirroring
 * {@code GetTicketQueryPlanIT}/{@code SupportQueueQueryPlanIT}'s plain
 * {@code EXPLAIN} pattern.
 */
@Tag("integration")
class TicketTimelineQueryPlanIT extends AbstractTicketTimelineIT {

    @Test
    void statusHistoryTicketPredicateShouldNotFullyScan() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedStatusHistory(ticketId, "NEW", "TRIAGING", DEFAULT_CREATED_AT.plusSeconds(60), 1);

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT history_id, occurred_at
            FROM ticket.ticket_status_history
            WHERE ticket_id = '%s'
            ORDER BY occurred_at ASC, history_id ASC
            """.formatted(ticketId), String.class);

        assertThat(String.join("\n", plan)).doesNotContain("Seq Scan on ticket_status_history");
    }

    @Test
    void messagesTicketPredicateShouldNotFullyScan() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "hello", DEFAULT_CREATED_AT.plusSeconds(60));

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT message_id, created_at
            FROM ticket.ticket_messages
            WHERE ticket_id = '%s'
            ORDER BY created_at ASC, message_id ASC
            """.formatted(ticketId), String.class);

        assertThat(String.join("\n", plan)).doesNotContain("Seq Scan on ticket_messages");
    }
}
