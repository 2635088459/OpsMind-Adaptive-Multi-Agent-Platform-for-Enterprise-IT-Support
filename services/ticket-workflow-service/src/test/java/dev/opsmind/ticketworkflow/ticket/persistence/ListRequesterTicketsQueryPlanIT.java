package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 §14/§18: the requester-scoped, sorted query uses the
 * {@code (requester_id, created_at DESC, ticket_id DESC)} index rather
 * than a full-table scan.
 */
@Tag("integration")
class ListRequesterTicketsQueryPlanIT extends AbstractListRequesterTicketsIT {

    @Test
    void requesterListQueryShouldNotFullyScanTickets() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now);

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT ticket_id, display_id, title, application_code, status, priority, created_at, updated_at, version
            FROM ticket.tickets
            WHERE requester_id = '%s'
            ORDER BY created_at DESC, ticket_id DESC
            LIMIT 21
            """.formatted(DEFAULT_REQUESTER), String.class);

        String joinedPlan = String.join("\n", plan);
        assertThat(joinedPlan).doesNotContain("Seq Scan on tickets");
    }
}
