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
 * SPEC-TW-012 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketResolveIT}'s
 * shape. Reuses {@link AbstractTicketAssignmentIT#seedAssignedTicket}'s
 * {@code IN_PROGRESS} overload for the happy-path ticket state.
 */
@Tag("integration")
class TicketRequestUserInputIT extends AbstractTicketAssignmentIT {

    private static final String REQUEST_SCOPE = "ticket:request-user-input";
    private static final String PROMPT = "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.";

    private String requestUserInputToken(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(REQUEST_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    private ResponseEntity<String> requestUserInput(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/user-input-requests", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String prompt) {
        return "{\"prompt\":\"" + prompt + "\",\"requestedFields\":[\"screenshot\",\"vpnStatus\"],\"expiresAt\":\"2026-08-05T18:00:00Z\"}";
    }

    private Map<String, Object> userInputRequestRow(UUID ticketId) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_user_input_requests WHERE ticket_id = ? ORDER BY requested_at DESC LIMIT 1", ticketId
        );
    }

    @Test
    void shouldRequestUserInputOnAnInProgressTicketAndCreateAnOpenRequest() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestUserInput(
            ticketId, requestUserInputToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "request-input-key-1", requestBody(PROMPT)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody())
            .contains("\"status\":\"WAITING_FOR_USER\"")
            .contains("\"previousStatus\":\"IN_PROGRESS\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("WAITING_FOR_USER");
        assertThat(ticketRow.get("waiting_for_requester_since")).isNotNull();
        assertThat(ticketRow.get("approval_reference")).isNull();
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> requestRow = userInputRequestRow(ticketId);
        assertThat(requestRow.get("request_status")).isEqualTo("OPEN");
        assertThat(requestRow.get("prompt")).isEqualTo(PROMPT);
        assertThat(requestRow.get("requested_by_id")).isEqualTo("sam.support");
        assertThat(requestRow.get("resume_status")).isEqualTo("IN_PROGRESS");
        assertThat(requestRow.get("requested_fields")).isNotNull();

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-014'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.user-input-requested'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = requestUserInputToken("sam.support", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = requestUserInput(ticketId, token, "\"0\"", "request-input-key-replay", requestBody(PROMPT));
        ResponseEntity<String> replay = requestUserInput(ticketId, token, "\"0\"", "request-input-key-replay", requestBody(PROMPT));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.user-input-requested'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        Integer requestCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_user_input_requests WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void shouldRejectATicketAlreadyWaitingForUser() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = requestUserInputToken("sam.support", Set.of(DEFAULT_TEAM_ID));
        ResponseEntity<String> first = requestUserInput(ticketId, token, "\"0\"", "request-input-key-first", requestBody(PROMPT));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = requestUserInput(ticketId, token, "\"1\"", "request-input-key-second", requestBody(PROMPT));

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("USER_INPUT_REQUEST_ALREADY_OPEN");
        Integer requestCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_user_input_requests WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void shouldRejectATicketNotYetInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = requestUserInput(
            ticketId, requestUserInputToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "request-input-key-invalid-state", requestBody(PROMPT)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestUserInput(
            ticketId, requestUserInputToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"5\"", "request-input-key-stale", requestBody(PROMPT)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldRejectAnActorOutsideTheTicketsQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = requestUserInput(
            ticketId, requestUserInputToken("sam.support", Set.of("SOME-OTHER-TEAM")), "\"0\"", "request-input-key-forbidden", requestBody(PROMPT)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
    }
}
