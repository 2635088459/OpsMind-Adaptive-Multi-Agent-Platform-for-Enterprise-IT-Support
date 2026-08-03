package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-008 deviation #9: {@code If-Match} required (428 if missing/
 * blank), 412 + {@code ETag} + {@code details.currentVersion} if stale — for
 * all 3 routes. Neither mutates anything.
 */
@Tag("integration")
class TicketAssignmentOptimisticLockingIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReturn428WhenIfMatchIsMissingOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), null, UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(428));
        assertThat(response.getBody()).contains("PRECONDITION_REQUIRED");
        assertNothingMutatedAndStillTriaged(ticketId);
    }

    @Test
    void shouldReturn412WithCurrentVersionAndETagWhenIfMatchIsStaleOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"5\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(412));
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
        assertThat(response.getBody()).contains("\"currentVersion\":0");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertNothingMutatedAndStillTriaged(ticketId);
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissingOnReassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), null, UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(428));
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(0L);
    }

    @Test
    void shouldReturn412WithCurrentVersionAndETagWhenIfMatchIsStaleOnReassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"9\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(412));
        assertThat(response.getBody()).contains("\"currentVersion\":0");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissingOnUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), null, UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(428));
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");
    }

    @Test
    void shouldReturn412WithCurrentVersionAndETagWhenIfMatchIsStaleOnUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"3\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(412));
        assertThat(response.getBody()).contains("\"currentVersion\":0");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");
    }

    private void assertNothingMutatedAndStillTriaged(UUID ticketId) {
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
        assertThat(ticketRow(ticketId).get("version")).isEqualTo(0L);
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isNull();
        assertThat(countRows("ticket.ticket_assignment_history")).isZero();
        assertThat(countRows("ticket.ticket_status_history")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
        assertThat(countRows("ticket.outbox_events")).isZero();
    }
}
