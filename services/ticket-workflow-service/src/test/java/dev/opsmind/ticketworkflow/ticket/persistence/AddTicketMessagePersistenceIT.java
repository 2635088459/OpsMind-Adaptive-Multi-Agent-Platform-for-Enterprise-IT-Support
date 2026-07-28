package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class AddTicketMessagePersistenceIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldPersistAllFieldsForAnEmployeePublicMessage() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String location = response.getHeaders().getLocation().toString();
        UUID messageId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_messages WHERE message_id = ?", messageId
        );
        assertThat(row.get("ticket_id")).isEqualTo(ticketId);
        assertThat(row.get("message_type")).isEqualTo("PUBLIC_REQUESTER_MESSAGE");
        assertThat(row.get("visibility")).isEqualTo("PUBLIC");
        assertThat(row.get("author_type")).isEqualTo("EMPLOYEE");
        assertThat(row.get("author_id")).isEqualTo(DEFAULT_REQUESTER);
        assertThat(row.get("content")).isEqualTo(DEFAULT_CONTENT);
        assertThat(row.get("version")).isEqualTo(0L);
    }

    @Test
    void shouldPersistInternalNoteWithInternalVisibility() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", java.util.Set.of("tickets:message:internal"), java.util.List.of(DEFAULT_APPLICATION_CODE)),
            UUID.randomUUID().toString(),
            supportBody("Identity verification is still required.", "INTERNAL_SUPPORT_NOTE")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        Integer internalCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_messages WHERE ticket_id = ? AND visibility = 'INTERNAL'",
            Integer.class, ticketId
        );
        assertThat(internalCount).isEqualTo(1);
    }
}
