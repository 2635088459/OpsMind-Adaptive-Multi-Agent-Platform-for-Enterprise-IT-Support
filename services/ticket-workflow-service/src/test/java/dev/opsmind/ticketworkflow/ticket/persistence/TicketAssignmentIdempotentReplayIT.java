package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-008 AC-08: the same key + identical request replays the stored response without a second mutation, for all 3 operations. */
@Tag("integration")
class TicketAssignmentIdempotentReplayIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReplayTheOriginalResponseForAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));
        String body = assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON);

        ResponseEntity<String> first = assign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = assign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(1L);
    }

    @Test
    void shouldReplayTheOriginalResponseForReassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));
        String body = assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON);

        ResponseEntity<String> first = reassign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = reassign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(1L);
    }

    @Test
    void shouldReplayTheOriginalResponseForUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        String idempotencyKey = UUID.randomUUID().toString();
        String bearerToken = supportToken("support-100", Set.of(DEFAULT_TEAM_ID));
        String body = unassignRequestBody(DEFAULT_REASON);

        ResponseEntity<String> first = unassign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = unassign(ticketId, bearerToken, "\"0\"", idempotencyKey, body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(countRows("ticket.ticket_assignment_history")).isEqualTo(1);
        assertThat(countRows("ticket.ticket_status_history")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(1L);
    }
}
