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
 * SPEC-TW-007 AC-07: a Requester (EMPLOYEE) is always rejected with {@code
 * TRIAGE_NOT_ALLOWED}; an otherwise-eligible actor missing {@code
 * ticket:triage} or the target queue's team grant is rejected with {@code
 * QUEUE_ACCESS_DENIED}; a matching grant succeeds (deviation #5).
 */
@Tag("integration")
class TriageTicketAuthorizationIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn403TriageNotAllowedForAnEmployeeActor() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, employeeToken(DEFAULT_REQUESTER), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("TRIAGE_NOT_ALLOWED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }

    @Test
    void shouldReturn403QueueAccessDeniedWhenTheActorLacksTheTriageScope() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportTokenWithoutTriageScope("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }

    @Test
    void shouldReturn403QueueAccessDeniedWhenSupportTeamsClaimDoesNotIncludeTheQueuesTeam() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of("SOME-OTHER-TEAM")), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }

    @Test
    void shouldSucceedWhenTheActorIsGrantedTheQueuesTeam() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }
}
