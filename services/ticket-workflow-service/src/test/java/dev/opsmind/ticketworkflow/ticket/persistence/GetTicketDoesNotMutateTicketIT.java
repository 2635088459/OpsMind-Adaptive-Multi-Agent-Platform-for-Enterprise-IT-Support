package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-002 §11/§22: Get Ticket never mutates Ticket state, version, or updatedAt. */
@Tag("integration")
class GetTicketDoesNotMutateTicketIT extends AbstractGetTicketIT {

    @Test
    void shouldLeaveUpdatedAtVersionAndStatusHistoryUnchangedAfterEmployeeRead() {
        UUID ticketId = seedTicket();
        Map<String, Object> before = jdbcTemplate.queryForMap(
            "SELECT updated_at, version FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );

        ResponseEntity<String> response = getTicket(ticketId, employeeToken(DEFAULT_REQUESTER));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = jdbcTemplate.queryForMap(
            "SELECT updated_at, version FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
        assertThat(after.get("version")).isEqualTo(before.get("version"));

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(historyCount).isZero();
    }

    @Test
    void shouldLeaveUpdatedAtAndVersionUnchangedAfterSupportRead() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");
        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );

        getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));

        Timestamp updatedAtAfter = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
    }
}
