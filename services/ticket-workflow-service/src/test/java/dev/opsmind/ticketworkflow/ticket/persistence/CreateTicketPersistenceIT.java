package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CreateTicketPersistenceIT extends AbstractCreateTicketIT {

    @Test
    void shouldPersistTicketWithExpectedInitialFields() {
        ResponseEntity<String> response = createTicket("user-persist-1", newIdempotencyKey(), validRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");

        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.tickets WHERE ticket_id = ?", ticketId
        );

        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("priority")).isEqualTo("UNASSIGNED");
        assertThat(row.get("requester_id")).isEqualTo("user-persist-1");
        assertThat(row.get("application_code")).isEqualTo("HOUSING_PORTAL");
        assertThat(row.get("source")).isEqualTo("PORTAL");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(row.get("active_workflow_id")).isNull();
        assertThat(row.get("resolved_at")).isNull();
        assertThat(row.get("closed_at")).isNull();
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(row.get("created_by_type")).isEqualTo("EMPLOYEE");
        assertThat(row.get("created_by_id")).isEqualTo("user-persist-1");
        assertThat(row.get("current_resolution_cycle_id")).isNotNull();
    }

    @Test
    void shouldPersistOneInitialStatusHistoryRecord() {
        ResponseEntity<String> response = createTicket("user-persist-2", newIdempotencyKey(), validRequestBody());
        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> history = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ?", ticketId
        );

        assertThat(history.get("from_status")).isNull();
        assertThat(history.get("to_status")).isEqualTo("NEW");
        assertThat(history.get("transition_id")).isEqualTo("SM-001");
        assertThat(history.get("reason_code")).isEqualTo("TICKET_CREATED");
        assertThat(history.get("aggregate_version")).isEqualTo(0L);
    }

    @Test
    void shouldPersistOneBusinessAuditRecord() {
        ResponseEntity<String> response = createTicket("user-persist-3", newIdempotencyKey(), validRequestBody());
        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );

        assertThat(audit.get("action")).isEqualTo("TICKET_CREATED");
        assertThat(audit.get("decision")).isEqualTo("ALLOWED");
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(audit.get("ticket_status_after")).isEqualTo("NEW");
        assertThat(audit.get("resource_type")).isEqualTo("TICKET");
    }

    @Test
    void shouldPersistOneTicketCreatedOutboxRecord() {
        ResponseEntity<String> response = createTicket("user-persist-4", newIdempotencyKey(), validRequestBody());
        UUID ticketId = extractTicketId(response.getHeaders().getLocation().toString());

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.outbox_events WHERE ticket_id = ?", ticketId
        );

        assertThat(outbox.get("event_type")).isEqualTo("ticket.created");
        assertThat(outbox.get("routing_key")).isEqualTo("ticket.created.v1");
        assertThat(outbox.get("aggregate_type")).isEqualTo("Ticket");
        assertThat(outbox.get("published_at")).isNull();
    }

    private UUID extractTicketId(String locationHeader) {
        String idSegment = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        return UUID.fromString(idSegment);
    }
}
