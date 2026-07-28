package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §1/§19: the Queue query never mutates the Ticket lifecycle —
 * status, version, and updatedAt stay unchanged, and no Status History row
 * is created.
 */
@Tag("integration")
class SupportQueueDoesNotMutateTicketIT extends AbstractSupportQueueIT {

    @Test
    void statusVersionAndUpdatedAtShouldBeUnchangedAfterQuerying() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID ticketId = seedTicket(DEFAULT_APPLICATION_CODE, "INVESTIGATING", now);

        Map<String, Object> before = jdbcTemplate.queryForMap(
            "SELECT status, version, updated_at FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = jdbcTemplate.queryForMap(
            "SELECT status, version, updated_at FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );

        assertThat(after).isEqualTo(before);
        assertThat(countRows("ticket.ticket_status_history")).isZero();
    }
}
