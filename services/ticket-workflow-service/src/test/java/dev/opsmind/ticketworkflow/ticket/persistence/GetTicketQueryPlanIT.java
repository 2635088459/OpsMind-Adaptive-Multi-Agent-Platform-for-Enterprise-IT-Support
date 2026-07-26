package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §20: Get Ticket queries use the Ticket PK and authorization
 * indexes rather than a full-table scan. {@code EXPLAIN} runs against the
 * same predicates {@code JdbcTicketQueryAdapter} issues.
 */
@Tag("integration")
class GetTicketQueryPlanIT extends AbstractGetTicketIT {

    @Test
    void employeeViewQueryShouldNotFullyScanTickets() {
        UUID ticketId = seedTicket();

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT t.ticket_id, t.version
            FROM ticket.tickets t
            LEFT JOIN ticket.ticket_sla_cycles s ON s.resolution_cycle_id = t.current_resolution_cycle_id
            WHERE t.ticket_id = '%s' AND t.requester_id = '%s'
            """.formatted(ticketId, DEFAULT_REQUESTER), String.class);

        String joinedPlan = String.join("\n", plan);
        assertThat(joinedPlan).doesNotContain("Seq Scan on tickets");
    }

    @Test
    void supportViewQueryShouldNotFullyScanTickets() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        List<String> plan = jdbcTemplate.queryForList("""
            EXPLAIN SELECT t.ticket_id, t.version
            FROM ticket.tickets t
            LEFT JOIN ticket.ticket_resolution_cycles rc ON rc.resolution_cycle_id = t.current_resolution_cycle_id
            LEFT JOIN ticket.ticket_sla_cycles s ON s.resolution_cycle_id = t.current_resolution_cycle_id
            WHERE t.ticket_id = '%s' AND t.application_code IN ('HOUSING_PORTAL')
            """.formatted(ticketId), String.class);

        String joinedPlan = String.join("\n", plan);
        assertThat(joinedPlan).doesNotContain("Seq Scan on tickets");
    }
}
