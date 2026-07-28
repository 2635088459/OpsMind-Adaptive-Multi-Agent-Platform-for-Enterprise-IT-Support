package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class TicketMessageAuditRedactionIT extends AbstractAddTicketMessageIT {

    private static final String DISTINCTIVE_CONTENT_MARKER = "CONTENT_MARKER_should_never_appear_in_audit";

    @Test
    void shouldNotStoreMessageContentOrIdempotencyKeyInAuditRecord() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = "secret-marker-idem-key-" + UUID.randomUUID();

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody(DISTINCTIVE_CONTENT_MARKER)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);

        String location = response.getHeaders().getLocation().toString();
        UUID messageId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", messageId.toString()
        );

        String auditRowAsText = audit.toString();
        assertThat(auditRowAsText).doesNotContain(DISTINCTIVE_CONTENT_MARKER);
        assertThat(auditRowAsText).doesNotContain(idempotencyKey);
    }

    @Test
    void shouldNotStoreInternalNoteContentInAuditRecordEither() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = "secret-marker-idem-key-" + UUID.randomUUID();

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", java.util.Set.of("tickets:message:internal"), java.util.List.of(DEFAULT_APPLICATION_CODE)),
            idempotencyKey,
            supportBody(DISTINCTIVE_CONTENT_MARKER, "INTERNAL_SUPPORT_NOTE")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);

        String location = response.getHeaders().getLocation().toString();
        UUID messageId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", messageId.toString()
        );

        String auditRowAsText = audit.toString();
        assertThat(auditRowAsText).doesNotContain(DISTINCTIVE_CONTENT_MARKER);
        assertThat(auditRowAsText).doesNotContain(idempotencyKey);
        assertThat(audit.get("action")).isEqualTo("TICKET_INTERNAL_NOTE_ADDED");
    }
}
