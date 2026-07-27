package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §1/§21: the list query never mutates Ticket state, version, or updatedAt. */
@Tag("integration")
class ListRequesterTicketsDoesNotMutateIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldLeaveUpdatedAtVersionAndStatusHistoryUnchangedAfterListing() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now);

        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );
        Long versionBefore = jdbcTemplate.queryForObject(
            "SELECT version FROM ticket.tickets WHERE ticket_id = ?", Long.class, ticketId
        );

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Timestamp updatedAtAfter = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );
        Long versionAfter = jdbcTemplate.queryForObject(
            "SELECT version FROM ticket.tickets WHERE ticket_id = ?", Long.class, ticketId
        );
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
        assertThat(versionAfter).isEqualTo(versionBefore);

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(historyCount).isZero();
    }
}
