package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §12: Add Message never updates Ticket status, version, or updatedAt. */
@Tag("integration")
class AddTicketMessageDoesNotMutateTicketIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldLeaveTicketStatusVersionAndUpdatedAtUnchanged() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "WAITING_FOR_USER");
        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );
        Long versionBefore = jdbcTemplate.queryForObject(
            "SELECT version FROM ticket.tickets WHERE ticket_id = ?", Long.class, ticketId
        );

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);

        String statusAfter = jdbcTemplate.queryForObject(
            "SELECT status FROM ticket.tickets WHERE ticket_id = ?", String.class, ticketId
        );
        Timestamp updatedAtAfter = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM ticket.tickets WHERE ticket_id = ?", Timestamp.class, ticketId
        );
        Long versionAfter = jdbcTemplate.queryForObject(
            "SELECT version FROM ticket.tickets WHERE ticket_id = ?", Long.class, ticketId
        );

        assertThat(statusAfter).isEqualTo("WAITING_FOR_USER");
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
        assertThat(versionAfter).isEqualTo(versionBefore);

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(historyCount).isZero();
    }

    @Test
    void resolvedTicketShouldAcceptFeedbackWithoutReopening() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "RESOLVED");

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String statusAfter = jdbcTemplate.queryForObject(
            "SELECT status FROM ticket.tickets WHERE ticket_id = ?", String.class, ticketId
        );
        assertThat(statusAfter).isEqualTo("RESOLVED");
    }
}
