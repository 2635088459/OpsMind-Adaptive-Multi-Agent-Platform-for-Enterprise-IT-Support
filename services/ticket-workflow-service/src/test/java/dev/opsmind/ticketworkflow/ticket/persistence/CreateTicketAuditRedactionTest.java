package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class CreateTicketAuditRedactionTest extends AbstractCreateTicketIT {

    private static final String DISTINCTIVE_TITLE = "TITLE_MARKER_should_never_appear_in_audit";
    private static final String DISTINCTIVE_DESCRIPTION = "DESCRIPTION_MARKER_should_never_appear_in_audit_either";

    @Test
    void shouldNotStoreTitleDescriptionOrIdempotencyKeyInAuditRecord() {
        String idempotencyKey = "secret-marker-idem-key-" + UUID.randomUUID();
        ResponseEntity<String> response = createTicket(
            "user-audit-redaction", idempotencyKey, validRequestBody(DISTINCTIVE_TITLE, DISTINCTIVE_DESCRIPTION)
        );

        assertThat(response.getStatusCode())
            .as("create ticket response body: %s", response.getBody())
            .isEqualTo(HttpStatus.CREATED);

        UUID ticketId = UUID.fromString(response.getHeaders().getLocation().toString()
            .substring(response.getHeaders().getLocation().toString().lastIndexOf('/') + 1));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );

        String auditRowAsText = audit.toString();
        assertThat(auditRowAsText).doesNotContain(DISTINCTIVE_TITLE);
        assertThat(auditRowAsText).doesNotContain(DISTINCTIVE_DESCRIPTION);
        assertThat(auditRowAsText).doesNotContain(idempotencyKey);
    }
}
