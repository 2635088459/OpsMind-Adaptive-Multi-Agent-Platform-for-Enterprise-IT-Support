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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-014 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketRequestUserInputIT}'s shape. Reuses {@link
 * AbstractTicketAssignmentIT#seedAssignedTicket}'s {@code IN_PROGRESS}
 * overload for the happy-path ticket state.
 */
@Tag("integration")
class TicketRequestApprovalIT extends AbstractTicketAssignmentIT {

    private static final String REQUEST_SCOPE = "ticket:request-approval";

    private String requestApprovalToken(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(REQUEST_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    private ResponseEntity<String> requestApproval(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/approval-requests", HttpMethod.POST, entity, String.class);
    }

    private String requestBody() {
        return """
            {"workflowId":"wf-9000","actionId":"act-100","actionType":"RESET_MFA","riskLevel":"HIGH",\
            "riskContext":{"targetSystem":"identity"},"reason":"MFA reset requires approval before execution."}""";
    }

    private Map<String, Object> approvalRequestRow(UUID ticketId) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_approval_requests WHERE ticket_id = ? ORDER BY requested_at DESC LIMIT 1", ticketId
        );
    }

    @Test
    void shouldRequestApprovalOnAnInProgressTicketAndCreateAnOpenRequest() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestApproval(
            ticketId, requestApprovalToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "request-approval-key-1", requestBody()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody())
            .contains("\"status\":\"WAITING_FOR_APPROVAL\"")
            .contains("\"previousStatus\":\"IN_PROGRESS\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(ticketRow.get("approval_reference")).isNotNull();
        assertThat(ticketRow.get("waiting_for_requester_since")).isNull();
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> requestRow = approvalRequestRow(ticketId);
        assertThat(requestRow.get("request_status")).isEqualTo("OPEN");
        assertThat(requestRow.get("workflow_id")).isEqualTo("wf-9000");
        assertThat(requestRow.get("action_id")).isEqualTo("act-100");
        assertThat(requestRow.get("action_type")).isEqualTo("RESET_MFA");
        assertThat(requestRow.get("risk_level")).isEqualTo("HIGH");
        assertThat(requestRow.get("requested_by_id")).isEqualTo("sam.support");
        assertThat(requestRow.get("risk_context")).isNotNull();
        assertThat(requestRow.get("approval_id")).isEqualTo(ticketRow.get("approval_reference"));

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-016'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.approval-wait-started'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = requestApprovalToken("sam.support", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = requestApproval(ticketId, token, "\"0\"", "request-approval-key-replay", requestBody());
        ResponseEntity<String> replay = requestApproval(ticketId, token, "\"0\"", "request-approval-key-replay", requestBody());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.approval-wait-started'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        Integer requestCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_approval_requests WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void shouldRejectATicketAlreadyWaitingForApproval() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = requestApprovalToken("sam.support", Set.of(DEFAULT_TEAM_ID));
        ResponseEntity<String> first = requestApproval(ticketId, token, "\"0\"", "request-approval-key-first", requestBody());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = requestApproval(ticketId, token, "\"1\"", "request-approval-key-second", requestBody());

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("APPROVAL_REQUEST_ALREADY_OPEN");
        Integer requestCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_approval_requests WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void shouldRejectATicketNotYetInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = requestApproval(
            ticketId, requestApprovalToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "request-approval-key-invalid-state", requestBody()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestApproval(
            ticketId, requestApprovalToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"5\"", "request-approval-key-stale", requestBody()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldRejectAnActorOutsideTheTicketsQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestApproval(
            ticketId, requestApprovalToken("sam.support", Set.of("SOME-OTHER-TEAM")), "\"0\"", "request-approval-key-forbidden", requestBody()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
    }
}
