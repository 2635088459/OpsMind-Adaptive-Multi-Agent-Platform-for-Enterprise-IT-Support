package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-11: the same key + identical request replays the stored response without a second mutation. */
@Tag("integration")
class TriageTicketIdempotentReplayIT extends AbstractTriageTicketIT {

    @Test
    void shouldReplayTheOriginalResponseForTheSameKeyAndSameRequest() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID subcategoryId = seedSubcategory(categoryId, true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = triageRequestBody(categoryId, subcategoryId, "HIGH", queueId);
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = triage(ticketId, bearerToken, "\"0\"", idempotencyKey, body);
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = triage(ticketId, bearerToken, "\"0\"", idempotencyKey, body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());

        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);
        assertThat(countRows("ticket.audit_records")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(1L);
    }
}
