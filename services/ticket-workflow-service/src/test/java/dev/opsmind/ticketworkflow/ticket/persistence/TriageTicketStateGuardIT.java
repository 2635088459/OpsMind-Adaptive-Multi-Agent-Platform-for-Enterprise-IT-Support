package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-007 AC-08: only {@code NEW} ("OPEN") may be triaged; any other
 * status returns 409 with both status values. The state check runs before
 * catalog validation (order documented on {@code
 * TriageTicketApplicationService}), so the request's {@code categoryId}/
 * {@code supportQueueId} never need to resolve to real catalog rows here —
 * random UUIDs are enough, which also avoids re-seeding {@code
 * ticket.support_queues} with a {@code team_id} that {@link
 * #seedTicketInStatus} already used (that column is unique).
 */
@Tag("integration")
class TriageTicketStateGuardIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn409WhenTheTicketIsAlreadyTriaged() {
        UUID ticketId = seedTicketInStatus(UUID.randomUUID(), "TRIAGED");

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(UUID.randomUUID(), null, "HIGH", UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"TRIAGED\"");
        assertThat(response.getBody()).contains("\"requiredStatus\":\"NEW\"");
    }

    @Test
    void shouldReturn409WhenTheTicketIsResolved() {
        UUID ticketId = seedTicketInStatus(UUID.randomUUID(), "RESOLVED");

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(UUID.randomUUID(), null, "HIGH", UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"RESOLVED\"");
    }

    /** TRIAGING (legacy, predates Phase 03) is not the same as TRIAGED and must still be rejected. */
    @Test
    void shouldReturn409WhenTheTicketIsInTheLegacyTriagingStatus() {
        UUID ticketId = seedTicketInStatus(UUID.randomUUID(), "TRIAGING");

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(UUID.randomUUID(), null, "HIGH", UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
        assertThat(response.getBody()).contains("\"currentStatus\":\"TRIAGING\"");
    }
}
