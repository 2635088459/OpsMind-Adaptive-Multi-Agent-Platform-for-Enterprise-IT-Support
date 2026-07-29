package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-03: a missing or inactive category is rejected with 422, and nothing is mutated. */
@Tag("integration")
class TriageTicketCategoryValidationIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn422WhenTheCategoryDoesNotExist() {
        UUID ticketId = seedOpenTicket();
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(UUID.randomUUID(), null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("TRIAGE_CATEGORY_INVALID");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.outbox_events")).isZero();
    }

    @Test
    void shouldReturn422WhenTheCategoryIsInactive() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(false);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("TRIAGE_CATEGORY_INVALID");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }
}
