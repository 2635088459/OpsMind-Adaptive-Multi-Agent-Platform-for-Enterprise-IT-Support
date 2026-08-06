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

import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-022 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketRequestApprovalIT}'s shape. The ticket is seeded directly in {@code
 * VERIFYING} (Phase 06's own IT tests establish that no "start execution"/
 * "complete execution" transition chain exists yet in this codebase) with a
 * {@code COMPLETED} {@code ticket_tool_execution_results} row — the tool
 * result this command must validate against.
 */
@Tag("integration")
class TicketStartVerificationIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:verification-start";

    private String serviceToken(String subject) {
        return TestJwtSupport.mintToken(subject, "verification-orchestrator-service", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "SERVICE"));
    }

    private ResponseEntity<String> startVerification(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/internal/v1/tickets/" + ticketId + "/verification/start", HttpMethod.POST, entity, String.class);
    }

    private String requestBody() {
        return """
            {"toolResultId":"tool-result-900","verificationType":"IDENTITY_LOGIN_CHECK",\
            "reason":"Confirm the requester can sign in after MFA reset."}""";
    }

    private UUID seedVerifyingTicketWithCompletedToolResult(String toolResultId) {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'VERIFYING' WHERE ticket_id = ?", ticketId);
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_tool_execution_results
                (tool_execution_id, ticket_id, workflow_id, action_id, authorization_reference,
                 result_status, tool_result_id, completed_at, event_id, recorded_at)
            VALUES ('exec-500', ?, 'wf-9000', 'act-100', 'auth-5678', 'COMPLETED', ?, ?, 'evt-completed-0', ?)
            """,
            ticketId, toolResultId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
        return ticketId;
    }

    private Map<String, Object> attemptRow(UUID ticketId) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_verification_attempts WHERE ticket_id = ? ORDER BY started_at DESC LIMIT 1", ticketId
        );
    }

    @Test
    void shouldStartVerificationOnAVerifyingTicketWithACompletedToolResult() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");

        ResponseEntity<String> response = startVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "start-verification-key-1", requestBody()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody())
            .contains("\"status\":\"VERIFYING\"")
            .contains("\"previousStatus\":\"VERIFYING\"")
            .contains("\"attemptNumber\":1")
            .contains("\"workflowId\":\"wf-9000\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("VERIFYING");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> attemptRow = attemptRow(ticketId);
        assertThat(attemptRow.get("tool_result_id")).isEqualTo("tool-result-900");
        assertThat(attemptRow.get("workflow_id")).isEqualTo("wf-9000");
        assertThat(attemptRow.get("attempt_number")).isEqualTo(1);
        assertThat(attemptRow.get("attempt_status")).isEqualTo("ACTIVE");
        assertThat(attemptRow.get("verification_type")).isEqualTo("IDENTITY_LOGIN_CHECK");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-025'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-started'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");
        String token = serviceToken("verification-orchestrator");

        ResponseEntity<String> first = startVerification(ticketId, token, "\"0\"", "start-verification-key-replay", requestBody());
        ResponseEntity<String> replay = startVerification(ticketId, token, "\"0\"", "start-verification-key-replay", requestBody());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-started'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        Integer attemptCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_verification_attempts WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(attemptCount).isEqualTo(1);
    }

    @Test
    void shouldRejectATicketNotYetVerifying() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = startVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "start-verification-key-invalid-state", requestBody()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAToolResultThatDoesNotBelongToThisTicket() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");

        ResponseEntity<String> response = startVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "start-verification-key-mismatch",
            """
            {"toolResultId":"tool-result-DOES-NOT-EXIST","verificationType":"IDENTITY_LOGIN_CHECK"}"""
        );

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("VERIFICATION_TOOL_RESULT_INVALID");
        Integer attemptCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_verification_attempts WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(attemptCount).isZero();
    }

    @Test
    void shouldRejectASecondActiveAttemptForTheSameToolResult() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");
        String token = serviceToken("verification-orchestrator");
        ResponseEntity<String> first = startVerification(ticketId, token, "\"0\"", "start-verification-key-first", requestBody());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = startVerification(ticketId, token, "\"1\"", "start-verification-key-second", requestBody());

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("VERIFICATION_ATTEMPT_ALREADY_ACTIVE");
        Integer attemptCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_verification_attempts WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(attemptCount).isEqualTo(1);
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");

        ResponseEntity<String> response = startVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"5\"", "start-verification-key-stale", requestBody()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldRejectANonServiceActorEvenWithTheRequiredScope() {
        UUID ticketId = seedVerifyingTicketWithCompletedToolResult("tool-result-900");
        String humanToken = TestJwtSupport.mintToken(
            "sam.support", "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT")
        );

        ResponseEntity<String> response = startVerification(ticketId, humanToken, "\"0\"", "start-verification-key-forbidden", requestBody());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }
}
