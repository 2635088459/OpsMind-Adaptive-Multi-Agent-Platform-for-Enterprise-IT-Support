package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-06: a missing or inactive support queue is rejected with 422. */
@Tag("integration")
class TriageTicketQueueValidationIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn422WhenTheQueueDoesNotExist() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("SUPPORT_QUEUE_INVALID");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }

    @Test
    void shouldReturn422WhenTheQueueIsInactive() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, false);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("SUPPORT_QUEUE_INVALID");
    }
}
