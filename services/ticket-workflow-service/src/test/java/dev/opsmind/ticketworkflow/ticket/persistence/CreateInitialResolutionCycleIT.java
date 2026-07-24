package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CreateInitialResolutionCycleIT extends AbstractCreateTicketIT {

    @Test
    void shouldCreateExactlyOneActiveResolutionCycleNumberOne() {
        ResponseEntity<String> response = createTicket("user-cycle-1", newIdempotencyKey(), validRequestBody());
        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> cycle = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE ticket_id = ?", ticketId
        );

        assertThat(cycle.get("cycle_number")).isEqualTo(1);
        assertThat(cycle.get("cycle_status")).isEqualTo("ACTIVE");
        assertThat(cycle.get("workflow_id")).isNull();
        assertThat(cycle.get("resolved_at")).isNull();

        Map<String, Object> ticket = jdbcTemplate.queryForMap(
            "SELECT current_resolution_cycle_id FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );
        assertThat(ticket.get("current_resolution_cycle_id")).isEqualTo(cycle.get("resolution_cycle_id"));
    }

    private UUID extractTicketId(String locationHeader) {
        String idSegment = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        return UUID.fromString(idSegment);
    }
}
