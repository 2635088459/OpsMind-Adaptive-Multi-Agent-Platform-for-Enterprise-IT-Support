package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §17, Definition of Done: the Timeline is a pure query — it never mutates the Ticket or its History/Message rows. */
@Tag("integration")
class TicketTimelineDoesNotMutateTicketIT extends AbstractTicketTimelineIT {

    @Test
    void shouldLeaveTicketStatusVersionAndUpdatedAtUnchanged() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedStatusHistory(ticketId, "NEW", "TRIAGING", DEFAULT_CREATED_AT.plusSeconds(60), 1);
        seedPublicRequesterMessage(ticketId, "hello", DEFAULT_CREATED_AT.plusSeconds(120));

        Map<String, Object> before = jdbcTemplate.queryForMap(
            "SELECT status, version, updated_at FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );
        int historyCountBefore = countRows("ticket.ticket_status_history");
        int messageCountBefore = countRows("ticket.ticket_messages");

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = jdbcTemplate.queryForMap(
            "SELECT status, version, updated_at FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );
        assertThat(after).isEqualTo(before);
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(historyCountBefore);
        assertThat(countRows("ticket.ticket_messages")).isEqualTo(messageCountBefore);
    }
}
