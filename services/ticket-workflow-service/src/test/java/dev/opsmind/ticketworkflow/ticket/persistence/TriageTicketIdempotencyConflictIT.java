package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-12: reusing an idempotency key with a different request hash is rejected with 409. */
@Tag("integration")
class TriageTicketIdempotencyConflictIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn409WhenTheSameKeyIsReusedWithADifferentPriority() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = triage(
            ticketId, bearerToken, "\"0\"", idempotencyKey, triageRequestBody(categoryId, null, "HIGH", queueId)
        );
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = triage(
            ticketId, bearerToken, "\"0\"", idempotencyKey, triageRequestBody(categoryId, null, "LOW", queueId)
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }
}
