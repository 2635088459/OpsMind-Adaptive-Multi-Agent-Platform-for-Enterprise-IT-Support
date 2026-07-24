package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CreateInitialSlaCycleIT extends AbstractCreateTicketIT {

    @Test
    void shouldCreateExactlyOneActiveSlaCycleNumberOne() {
        ResponseEntity<String> response = createTicket("user-sla-1", newIdempotencyKey(), validRequestBody());
        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> slaCycle = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_sla_cycles WHERE ticket_id = ?", ticketId
        );

        assertThat(slaCycle.get("cycle_number")).isEqualTo(1);
        assertThat(slaCycle.get("status")).isEqualTo("ACTIVE");
        assertThat(slaCycle.get("policy_id")).isEqualTo("DEFAULT");

        Timestamp createdAt = (Timestamp) slaCycle.get("created_at");
        Timestamp resolutionDueAt = (Timestamp) slaCycle.get("resolution_due_at");
        assertThat(resolutionDueAt).isNotNull();
        assertThat(resolutionDueAt).isAfterOrEqualTo(createdAt);

        Map<String, Object> resolutionCycle = jdbcTemplate.queryForMap(
            "SELECT resolution_cycle_id FROM ticket.ticket_resolution_cycles WHERE ticket_id = ?", ticketId
        );
        assertThat(slaCycle.get("resolution_cycle_id")).isEqualTo(resolutionCycle.get("resolution_cycle_id"));
    }

    private UUID extractTicketId(String locationHeader) {
        String idSegment = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        return UUID.fromString(idSegment);
    }
}
