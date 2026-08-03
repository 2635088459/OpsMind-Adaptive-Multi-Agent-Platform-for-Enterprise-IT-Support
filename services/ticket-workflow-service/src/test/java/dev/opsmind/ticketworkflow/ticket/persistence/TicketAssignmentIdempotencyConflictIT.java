package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-008 AC-08: reusing an idempotency key with a different assigneeId is rejected with 409. */
@Tag("integration")
class TicketAssignmentIdempotencyConflictIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReturn409WhenTheSameKeyIsReusedWithADifferentAssigneeOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = assign(ticketId, bearerToken, "\"0\"", idempotencyKey, assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON));
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = assign(ticketId, bearerToken, "\"0\"", idempotencyKey, assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    @Test
    void shouldReturn409WhenTheSameKeyIsReusedWithADifferentReasonOnUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = unassign(ticketId, bearerToken, "\"0\"", idempotencyKey, unassignRequestBody(DEFAULT_REASON));
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = unassign(ticketId, bearerToken, "\"0\"", idempotencyKey, unassignRequestBody("A different reason entirely"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
    }
}
