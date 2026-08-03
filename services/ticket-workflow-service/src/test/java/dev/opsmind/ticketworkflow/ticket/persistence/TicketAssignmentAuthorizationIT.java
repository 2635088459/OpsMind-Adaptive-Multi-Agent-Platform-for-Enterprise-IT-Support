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
 * SPEC-TW-008 deviation #8: an EMPLOYEE actor, or an IT_SUPPORT actor
 * missing {@code ticket:assign}, is rejected 403 FORBIDDEN; an actor
 * holding the scope but not granted the ticket's team is rejected 403
 * QUEUE_ACCESS_DENIED; a matching grant succeeds — for all 3 routes.
 */
@Tag("integration")
class TicketAssignmentAuthorizationIT extends AbstractTicketAssignmentIT {

    private void seedEligibleAssignee(UUID queueId, String assigneeId) {
        seedSupportAgent(assigneeId, "IT_SUPPORT", true);
        seedQueueMembership(assigneeId, queueId);
    }

    @Test
    void shouldReturn403ForbiddenForAnEmployeeActorOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedEligibleAssignee(queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = assign(
            ticketId, employeeToken(DEFAULT_REQUESTER), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
    }

    @Test
    void shouldReturn403ForbiddenForAnItSupportActorMissingTheAssignScopeOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedEligibleAssignee(queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = assign(
            ticketId, supportTokenWithoutAssignScope("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldReturn403QueueAccessDeniedWhenTheActorsTeamsDoNotIncludeTheTicketsTeamOnAssign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedEligibleAssignee(queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of("SOME-OTHER-TEAM")), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("TRIAGED");
    }

    @Test
    void shouldSucceedOnAssignWhenTheActorIsGrantedTheTicketsTeam() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(DEFAULT_TEAM_ID, queueId);
        seedEligibleAssignee(queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = assign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(DEFAULT_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturn403ForbiddenForAnEmployeeActorOnReassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedEligibleAssignee(queueId, OTHER_ASSIGNEE_ID);

        ResponseEntity<String> response = reassign(
            ticketId, employeeToken(DEFAULT_REQUESTER), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldReturn403QueueAccessDeniedOnReassignWhenTeamDoesNotMatch() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedEligibleAssignee(queueId, OTHER_ASSIGNEE_ID);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of("SOME-OTHER-TEAM")), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
    }

    @Test
    void shouldSucceedOnReassignWhenTheActorIsGrantedTheTicketsTeam() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedEligibleAssignee(queueId, OTHER_ASSIGNEE_ID);

        ResponseEntity<String> response = reassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            assignRequestBody(OTHER_ASSIGNEE_ID, DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturn403ForbiddenForAnEmployeeActorOnUnassign() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, employeeToken(DEFAULT_REQUESTER), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldReturn403QueueAccessDeniedOnUnassignWhenTeamDoesNotMatch() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of("SOME-OTHER-TEAM")), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
    }

    @Test
    void shouldSucceedOnUnassignWhenTheActorIsGrantedTheTicketsTeam() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = unassign(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            unassignRequestBody(DEFAULT_REASON)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }
}
