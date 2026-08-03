package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-008 AC-05: assignee eligibility (missing/inactive/not-queue-member) for both assign and reassign. */
@Tag("integration")
class TicketAssignmentEligibilityIT extends AbstractTicketAssignmentIT {

    @Test
    void shouldReturn404WhenTheAssignAssigneeDoesNotExist() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody("nonexistent-agent", DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ASSIGNEE_NOT_FOUND");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
    }

    @Test
    void shouldReturn409WhenTheAssignAssigneeIsInactive() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", false);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("ASSIGNEE_INACTIVE");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
    }

    @Test
    void shouldReturn409WhenTheAssignAssigneeIsNotAMemberOfTheTicketsQueue() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        // Deliberately not seeding a queue membership row.

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("ASSIGNEE_NOT_IN_QUEUE");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
    }

    @Test
    void shouldReturn404WhenTheReassignNewAssigneeDoesNotExist() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody("nonexistent-agent", DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ASSIGNEE_NOT_FOUND");
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
    }

    @Test
    void shouldReturn409WhenTheReassignNewAssigneeIsInactive() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", false);
        seedQueueMembership(OTHER_ASSIGNEE_ID, queueId);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("ASSIGNEE_INACTIVE");
    }

    @Test
    void shouldReturn409WhenTheReassignNewAssigneeIsNotAMemberOfTheTicketsQueue() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(OTHER_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("ASSIGNEE_NOT_IN_QUEUE");
    }
}
