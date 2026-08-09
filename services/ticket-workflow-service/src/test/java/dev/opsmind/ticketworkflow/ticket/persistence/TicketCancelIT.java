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

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-029 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketConfirmResolutionIT}'s (SPEC-TW-026) shape. Golden-path tokens also
 * carry valid SPEC-TW-036 step-up claims, since Cancel is a phase-09
 * high-risk command that now requires them.
 */
@Tag("integration")
class TicketCancelIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:cancel";
    private static final String REASON = "The requester no longer needs this request.";

    private Map<String, Object> validStepUpClaims() {
        Instant now = Instant.now();
        return Map.of(
            "step_up_proof_id", "proof-" + UUID.randomUUID(),
            "step_up_method", "MFA_TOTP",
            "step_up_verified_at", now.getEpochSecond(),
            "step_up_expires_at", now.plusSeconds(3600).getEpochSecond()
        );
    }

    private String employeeTokenWithScope(String subject) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("actor_type", "EMPLOYEE"));
        claims.putAll(validStepUpClaims());
        return TestJwtSupport.mintToken(subject, "employee-portal", Set.of(REQUIRED_SCOPE), claims);
    }

    private String supportTokenWithScope(String subject) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("actor_type", "IT_SUPPORT"));
        claims.putAll(validStepUpClaims());
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), claims);
    }

    private ResponseEntity<String> cancel(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/cancel", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String reasonCode) {
        return "{\"cancelReasonCode\":\"" + reasonCode + "\",\"cancelReason\":\"" + REASON + "\"}";
    }

    private Map<String, Object> resolutionCycleRow(UUID ticketId, int cycleNumber) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE ticket_id = ? AND cycle_number = ?", ticketId, cycleNumber
        );
    }

    @Test
    void shouldCancelAnInProgressTicketAsTheTicketsOwnRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = cancel(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "cancel-key-1", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"CANCELLED\"")
            .contains("\"previousStatus\":\"IN_PROGRESS\"")
            .contains("\"cancelReasonCode\":\"NO_LONGER_NEEDED\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("CANCELLED");
        assertThat(ticketRow.get("cancel_reason_code")).isEqualTo("NO_LONGER_NEEDED");
        assertThat(ticketRow.get("cancelled_at")).isNotNull();
        assertThat(ticketRow.get("active_workflow_id")).isNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> cycleRow = resolutionCycleRow(ticketId, 1);
        assertThat(cycleRow.get("cycle_status")).isEqualTo("CANCELLED");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-034'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.cancelled'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldCancelAsAnAuthorizedSupportActorEvenWithoutQueueMembership() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = cancel(
            ticketId, supportTokenWithScope("sam.support"), "\"0\"", "cancel-key-support", requestBody("SUPPORT_CANCELLED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"cancelReasonCode\":\"SUPPORT_CANCELLED\"");
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = employeeTokenWithScope(DEFAULT_REQUESTER);

        ResponseEntity<String> first = cancel(ticketId, token, "\"0\"", "cancel-key-replay", requestBody("NO_LONGER_NEEDED"));
        ResponseEntity<String> replay = cancel(ticketId, token, "\"0\"", "cancel-key-replay", requestBody("NO_LONGER_NEEDED"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.cancelled'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = cancel(
            ticketId, employeeTokenWithScope("someone-else"), "\"0\"", "cancel-key-forbidden", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String tokenWithoutScope = TestJwtSupport.mintToken(DEFAULT_REQUESTER, "employee-portal", Set.of(), Map.of("actor_type", "EMPLOYEE"));

        ResponseEntity<String> response = cancel(
            ticketId, tokenWithoutScope, "\"0\"", "cancel-key-no-scope", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectATerminalTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update(
            "UPDATE ticket.tickets SET status = 'CANCELLED', cancelled_at = now(), cancel_reason_code = 'NO_LONGER_NEEDED' WHERE ticket_id = ?",
            ticketId
        );

        ResponseEntity<String> response = cancel(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "cancel-key-terminal", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = cancel(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"5\"", "cancel-key-stale", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    /** SPEC-TW-036: Cancel is a phase-09 high-risk command — a token with the required scope but no step-up claims is still rejected. */
    @Test
    void shouldRejectAnOtherwiseAuthorizedRequestMissingStepUpProof() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String tokenWithoutStepUp = TestJwtSupport.mintToken(
            DEFAULT_REQUESTER, "employee-portal", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "EMPLOYEE")
        );

        ResponseEntity<String> response = cancel(
            ticketId, tokenWithoutStepUp, "\"0\"", "cancel-key-no-step-up", requestBody("NO_LONGER_NEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("STEP_UP_REQUIRED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }
}
