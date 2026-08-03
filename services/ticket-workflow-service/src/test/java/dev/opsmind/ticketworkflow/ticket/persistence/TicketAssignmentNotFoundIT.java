package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-008: a nonexistent ticket returns 404 TICKET_NOT_FOUND for all 3 routes. */
@Tag("integration")
class TicketAssignmentNotFoundIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReturn404ForANonexistentTicketOnAssign() {
        ResponseEntity<String> response = assign(
            UUID.randomUUID(), supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }

    @Test
    void shouldReturn404ForANonexistentTicketOnReassign() {
        ResponseEntity<String> response = reassign(
            UUID.randomUUID(), supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }

    @Test
    void shouldReturn404ForANonexistentTicketOnUnassign() {
        ResponseEntity<String> response = unassign(
            UUID.randomUUID(), supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }
}
