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
 * SPEC-TW-031 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketCancelIT}'s
 * (SPEC-TW-029) shape. Golden-path tokens also carry valid SPEC-TW-036
 * step-up claims, since Escalate is a phase-09 high-risk command that now
 * requires them.
 */
@Tag("integration")
class TicketEscalateIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:escalate";
    private static final String REASON = "Customer-facing outage with broad user impact.";

    private Map<String, Object> validStepUpClaims() {
        Instant now = Instant.now();
        return Map.of(
            "step_up_proof_id", "proof-" + UUID.randomUUID(),
            "step_up_method", "MFA_TOTP",
            "step_up_verified_at", now.getEpochSecond(),
            "step_up_expires_at", now.plusSeconds(3600).getEpochSecond()
        );
    }

    private String supportTokenWithScope(String subject) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("actor_type", "IT_SUPPORT"));
        claims.putAll(validStepUpClaims());
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), claims);
    }

    private String serviceTokenWithScope(String subject) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("actor_type", "SERVICE"));
        claims.putAll(validStepUpClaims());
        return TestJwtSupport.mintToken(subject, "escalation-policy-service", Set.of(REQUIRED_SCOPE), claims);
    }

    private ResponseEntity<String> escalate(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/escalation", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String escalationReasonCode) {
        return "{\"escalationReasonCode\":\"" + escalationReasonCode + "\",\"escalationReason\":\"" + REASON + "\"}";
    }

    @Test
    void shouldEscalateATicketAndFreezeAutomatedProgression() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update("UPDATE ticket.tickets SET active_workflow_id = 'wf-42' WHERE ticket_id = ?", ticketId);

        ResponseEntity<String> response = escalate(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "escalate-key-1", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"ESCALATED\"")
            .contains("\"previousStatus\":\"IN_PROGRESS\"")
            .contains("\"escalationReasonCode\":\"USER_IMPACT\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("ESCALATED");
        assertThat(ticketRow.get("escalation_reason_code")).isEqualTo("USER_IMPACT");
        assertThat(ticketRow.get("escalated_by")).isEqualTo("lead.sam");
        assertThat(ticketRow.get("escalated_at")).isNotNull();
        assertThat(ticketRow.get("active_workflow_id")).isNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> historyRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ? ORDER BY aggregate_version DESC LIMIT 1", ticketId
        );
        assertThat(historyRow.get("from_status")).isEqualTo("IN_PROGRESS");
        assertThat(historyRow.get("to_status")).isEqualTo("ESCALATED");
        assertThat(historyRow.get("transition_id")).isEqualTo("SM-043");

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.escalated'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldAllowAServicePolicyWorkerActor() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = escalate(
            ticketId, serviceTokenWithScope("escalation-policy-worker"), "\"0\"", "escalate-key-service", requestBody("REPEATED_FAILURE")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ticketRow(ticketId).get("escalated_by")).isEqualTo("escalation-policy-worker");
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = supportTokenWithScope("lead.sam");

        ResponseEntity<String> first = escalate(ticketId, token, "\"0\"", "escalate-key-replay", requestBody("USER_IMPACT"));
        ResponseEntity<String> replay = escalate(ticketId, token, "\"0\"", "escalate-key-replay", requestBody("USER_IMPACT"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.escalated'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String tokenWithoutScope = TestJwtSupport.mintToken("lead.sam", "support-console", Set.of(), Map.of("actor_type", "IT_SUPPORT"));

        ResponseEntity<String> response = escalate(
            ticketId, tokenWithoutScope, "\"0\"", "escalate-key-no-scope", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectATerminalStatus() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketWithAssignee(DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        jdbcTemplate.update(
            "UPDATE ticket.tickets SET status = 'CANCELLED', cancelled_at = now(), cancel_reason_code = 'NO_LONGER_NEEDED' WHERE ticket_id = ?",
            ticketId
        );

        ResponseEntity<String> response = escalate(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "escalate-key-terminal", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
    }

    @Test
    void shouldRejectAnAlreadyEscalatedTicket() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update(
            "UPDATE ticket.tickets SET status = 'ESCALATED', escalated_at = now(), escalated_by = 'lead.sam', escalation_reason_code = 'USER_IMPACT' WHERE ticket_id = ?",
            ticketId
        );

        ResponseEntity<String> response = escalate(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "escalate-key-already", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = escalate(
            ticketId, supportTokenWithScope("lead.sam"), "\"5\"", "escalate-key-stale", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldReturn404ForAMissingTicket() {
        ResponseEntity<String> response = escalate(
            UUID.randomUUID(), supportTokenWithScope("lead.sam"), "\"0\"", "escalate-key-missing", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    /** SPEC-TW-036: Escalate is a phase-09 high-risk command — a token with the required scope but no step-up claims is still rejected. */
    @Test
    void shouldRejectAnOtherwiseAuthorizedRequestMissingStepUpProof() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String tokenWithoutStepUp = TestJwtSupport.mintToken(
            "lead.sam", "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT")
        );

        ResponseEntity<String> response = escalate(
            ticketId, tokenWithoutStepUp, "\"0\"", "escalate-key-no-step-up", requestBody("USER_IMPACT")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("STEP_UP_REQUIRED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }
}
