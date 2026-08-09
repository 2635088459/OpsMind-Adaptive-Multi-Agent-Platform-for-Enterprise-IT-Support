package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import dev.opsmind.ticketworkflow.support.TestJwtSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-030 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketCancelIT}'s
 * (SPEC-TW-029) shape.
 */
@Tag("integration")
class TicketUpdateAssignmentIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:assign-route";
    private static final String OTHER_TEAM_ID = "TEAM-ROUTING";
    private static final String REASON = "Rebalancing queue load across teams.";

    private String leadTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT"));
    }

    private String routerTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "assignment-router-service", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "SERVICE"));
    }

    private ResponseEntity<String> updateAssignment(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/assignment", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(UUID supportQueueId, String assigneeId) {
        String assigneeJson = assigneeId == null ? "null" : "\"" + assigneeId + "\"";
        return "{\"supportQueueId\":\"" + supportQueueId + "\",\"assigneeId\":" + assigneeJson + ",\"reason\":\"" + REASON + "\"}";
    }

    @Test
    void shouldRouteATicketToADifferentTeamQueueAndAssignee() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);
        seedSupportAgent("alex.support", "IT_SUPPORT", true);
        seedQueueMembership("alex.support", targetQueueId);

        ResponseEntity<String> response = updateAssignment(
            ticketId, leadTokenWithScope("lead.sam"), "\"0\"", "route-key-1", requestBody(targetQueueId, "alex.support")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"IN_PROGRESS\"")
            .contains("\"teamId\":\"" + OTHER_TEAM_ID + "\"")
            .contains("\"assigneeId\":\"alex.support\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("current_team_id")).isEqualTo(OTHER_TEAM_ID);
        assertThat(ticketRow.get("support_queue_id")).isEqualTo(targetQueueId);
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo("alex.support");
        assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_assignment_history WHERE ticket_id = ? AND action = 'ROUTED'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.assignment-updated'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldAllowAnAutomatedRouterActor() {
        // TRIAGED (not a work-state) so clearing/omitting the assignee while
        // moving teams does not conflict with V015's
        // ck_tickets_work_states_have_assignee CHECK constraint.
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId);

        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);

        ResponseEntity<String> response = updateAssignment(
            ticketId, routerTokenWithScope("assignment-router"), "\"0\"", "route-key-router", requestBody(targetQueueId, null)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"teamId\":\"" + OTHER_TEAM_ID + "\"");
        assertThat(ticketRow(ticketId).get("current_support_user_id")).isNull();
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        // TRIAGED (not a work-state) for the same reason as
        // shouldAllowAnAutomatedRouterActor above.
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedTriagedTicketReadyToAssign(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId);
        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);
        String token = leadTokenWithScope("lead.sam");

        ResponseEntity<String> first = updateAssignment(ticketId, token, "\"0\"", "route-key-replay", requestBody(targetQueueId, null));
        ResponseEntity<String> replay = updateAssignment(ticketId, token, "\"0\"", "route-key-replay", requestBody(targetQueueId, null));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.assignment-updated'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);
        String tokenWithoutScope = TestJwtSupport.mintToken("lead.sam", "support-console", Set.of(), Map.of("actor_type", "IT_SUPPORT"));

        ResponseEntity<String> response = updateAssignment(
            ticketId, tokenWithoutScope, "\"0\"", "route-key-no-scope", requestBody(targetQueueId, null)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectAnInvalidTargetQueue() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = updateAssignment(
            ticketId, leadTokenWithScope("lead.sam"), "\"0\"", "route-key-bad-queue", requestBody(UUID.randomUUID(), null)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("SUPPORT_QUEUE_INVALID");
    }

    @Test
    void shouldRejectAnAssigneeThatIsNotAMemberOfTheTargetQueue() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);
        seedSupportAgent("alex.support", "IT_SUPPORT", true);
        // Deliberately not a member of targetQueueId.

        ResponseEntity<String> response = updateAssignment(
            ticketId, leadTokenWithScope("lead.sam"), "\"0\"", "route-key-not-in-queue", requestBody(targetQueueId, "alex.support")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("ASSIGNEE_NOT_IN_QUEUE");
    }

    @Test
    void shouldRejectWhenNothingWouldActuallyChange() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        // The eligibility check runs before the domain's no-op guard, so the
        // current assignee must resolve as a real, eligible agent even
        // though nothing about it is expected to change.
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        seedQueueMembership(DEFAULT_ASSIGNEE_ID, sourceQueueId);

        ResponseEntity<String> response = updateAssignment(
            ticketId, leadTokenWithScope("lead.sam"), "\"0\"", "route-key-no-change", requestBody(sourceQueueId, DEFAULT_ASSIGNEE_ID)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID sourceQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, sourceQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        UUID targetQueueId = seedSupportQueue(OTHER_TEAM_ID, true);

        ResponseEntity<String> response = updateAssignment(
            ticketId, leadTokenWithScope("lead.sam"), "\"5\"", "route-key-stale", requestBody(targetQueueId, null)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }
}
