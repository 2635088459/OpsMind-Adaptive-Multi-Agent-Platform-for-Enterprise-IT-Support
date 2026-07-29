package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-09: an unknown ticket returns 404 (no tenant concept exists in this codebase, deviation #1). */
@Tag("integration")
class TriageTicketNotFoundIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn404ForANonexistentTicket() {
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            UUID.randomUUID(), supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }
}
