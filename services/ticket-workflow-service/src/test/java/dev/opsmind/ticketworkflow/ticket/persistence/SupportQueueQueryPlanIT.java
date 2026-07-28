package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §20: the default-queue application/status predicate uses the partial index, not a full scan. */
@Tag("integration")
class SupportQueueQueryPlanIT extends AbstractSupportQueueIT {

    @Test
    void supportQueueApplicationAndStatusPredicateShouldNotFullyScanTickets() {
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-23T16:30:00Z"));

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT ticket_id
            FROM ticket.tickets
            WHERE status NOT IN ('CLOSED', 'CANCELLED')
              AND application_code = '%s'
            """.formatted(DEFAULT_APPLICATION_CODE), String.class);

        String joinedPlan = String.join("\n", plan);
        assertThat(joinedPlan).doesNotContain("Seq Scan on tickets");
    }
}
