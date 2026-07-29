package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-01: full happy path, asserting every persisted field and the atomic side-writes. */
@Tag("integration")
class TriageTicketSuccessIT extends AbstractTriageTicketIT {

    @Test
    void shouldTriageAnOpenTicketAndPersistEveryExpectedField() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID subcategoryId = seedSubcategory(categoryId, true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, subcategoryId, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("status")).isEqualTo("TRIAGED");
        assertThat(row.get("category_id")).isEqualTo(categoryId);
        assertThat(row.get("subcategory_id")).isEqualTo(subcategoryId);
        assertThat(row.get("priority")).isEqualTo("HIGH");
        assertThat(row.get("support_queue_id")).isEqualTo(queueId);
        assertThat(row.get("triaged_by")).isEqualTo("support-100");
        assertThat(row.get("triaged_at")).isNotNull();
        assertThat(row.get("current_team_id")).isEqualTo(DEFAULT_TEAM_ID);
        assertThat(row.get("version")).isEqualTo(1L);

        Map<String, Object> history = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ?", ticketId
        );
        assertThat(history.get("from_status")).isEqualTo("NEW");
        assertThat(history.get("to_status")).isEqualTo("TRIAGED");
        assertThat(history.get("transition_id")).isEqualTo("SM-002");
        assertThat(history.get("reason_code")).isEqualTo("TICKET_TRIAGED");
        assertThat(history.get("aggregate_version")).isEqualTo(1L);
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );
        assertThat(audit.get("action")).isEqualTo("TICKET_TRIAGED");
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(audit.get("ticket_status_before")).isEqualTo("NEW");
        assertThat(audit.get("ticket_status_after")).isEqualTo("TRIAGED");
        assertThat(countRows("ticket.audit_records")).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.outbox_events WHERE ticket_id = ?", ticketId
        );
        assertThat(outbox.get("event_type")).isEqualTo("ticket.triaged");
        assertThat(outbox.get("event_version")).isEqualTo("1.0");
        assertThat(outbox.get("routing_key")).isEqualTo("ticket.triaged.v1");
        assertThat(outbox.get("aggregate_type")).isEqualTo("Ticket");
        assertThat(outbox.get("published_at")).isNull();
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    @Test
    void shouldAllowCriticalPriorityUnderTheOrdinaryTriageScope() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "CRITICAL", queueId)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ticketRow(ticketId).get("priority")).isEqualTo("CRITICAL");
    }
}
