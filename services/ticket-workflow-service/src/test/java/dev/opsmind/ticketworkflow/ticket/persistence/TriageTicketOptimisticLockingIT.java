package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-10: a missing {@code If-Match} is 428; a stale version is 412 with the current version and ETag; neither mutates anything. */
@Tag("integration")
class TriageTicketOptimisticLockingIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn428WhenIfMatchIsMissing() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), null, UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(428));
        assertThat(response.getBody()).contains("PRECONDITION_REQUIRED");
        assertNothingMutated(ticketId);
    }

    @Test
    void shouldReturn412WithCurrentVersionAndETagWhenIfMatchIsStale() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"5\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(412));
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
        assertThat(response.getBody()).contains("\"currentVersion\":0");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertNothingMutated(ticketId);
    }

    private void assertNothingMutated(UUID ticketId) {
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(0L);
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
        assertThat(countRows("ticket.outbox_events")).isZero();
    }
}
