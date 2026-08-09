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
 * SPEC-TW-032 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketEscalateIT}'s
 * (SPEC-TW-031) shape.
 */
@Tag("integration")
class TicketResumeEscalatedTicketIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:escalation-resume";
    private static final String REASON = "Root cause identified and mitigated; resuming active work.";

    private String supportTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT"));
    }

    private ResponseEntity<String> resume(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/escalation/resume", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String resumeReasonCode) {
        return "{\"resumeReasonCode\":\"" + resumeReasonCode + "\",\"resumeReason\":\"" + REASON + "\"}";
    }

    private UUID seedEscalatedTicket(UUID ticketId, String teamId, UUID supportQueueId, String assigneeId) {
        seedAssignedTicket(ticketId, teamId, supportQueueId, assigneeId, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update(
            "UPDATE ticket.tickets SET status = 'ESCALATED', escalated_at = now(), escalated_by = 'lead.sam', " +
                "escalation_reason_code = 'USER_IMPACT' WHERE ticket_id = ?",
            ticketId
        );
        return ticketId;
    }

    @Test
    void shouldResumeAnEscalatedTicketAndPreserveTheEscalationAuditTrail() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedEscalatedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = resume(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "resume-key-1", requestBody("ROOT_CAUSE_RESOLVED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        // DEFAULT_ASSIGNEE_ID was never seeded into ticket.support_agents,
        // so it reports as ASSIGNEE_INACTIVE, not UNASSIGNED — the ticket
        // does have an owner, just not one the agent directory knows.
        assertThat(response.getBody())
            .contains("\"status\":\"IN_PROGRESS\"")
            .contains("\"previousStatus\":\"ESCALATED\"")
            .contains("\"resumeReasonCode\":\"ROOT_CAUSE_RESOLVED\"")
            .contains("\"ownershipStatus\":\"ASSIGNEE_INACTIVE\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(ticketRow.get("escalation_resume_reason_code")).isEqualTo("ROOT_CAUSE_RESOLVED");
        assertThat(ticketRow.get("escalation_resumed_by")).isEqualTo("lead.sam");
        assertThat(ticketRow.get("escalation_resumed_at")).isNotNull();
        // The escalation audit trail is preserved, not discarded.
        assertThat(ticketRow.get("escalation_reason_code")).isEqualTo("USER_IMPACT");
        assertThat(ticketRow.get("escalated_by")).isEqualTo("lead.sam");
        assertThat(ticketRow.get("escalated_at")).isNotNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> historyRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_status_history WHERE ticket_id = ? ORDER BY aggregate_version DESC LIMIT 1", ticketId
        );
        assertThat(historyRow.get("from_status")).isEqualTo("ESCALATED");
        assertThat(historyRow.get("to_status")).isEqualTo("IN_PROGRESS");
        assertThat(historyRow.get("transition_id")).isEqualTo("SM-049");

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.escalation-resumed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReportAnActiveOwnershipStatusWhenTheAssigneeIsStillActive() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedEscalatedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> response = resume(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "resume-key-active", requestBody("MITIGATION_APPLIED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"ownershipStatus\":\"ACTIVE\"");
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedEscalatedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        String token = supportTokenWithScope("lead.sam");

        ResponseEntity<String> first = resume(ticketId, token, "\"0\"", "resume-key-replay", requestBody("ROOT_CAUSE_RESOLVED"));
        ResponseEntity<String> replay = resume(ticketId, token, "\"0\"", "resume-key-replay", requestBody("ROOT_CAUSE_RESOLVED"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.escalation-resumed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedEscalatedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);
        String tokenWithoutScope = TestJwtSupport.mintToken("lead.sam", "support-console", Set.of(), Map.of("actor_type", "IT_SUPPORT"));

        ResponseEntity<String> response = resume(
            ticketId, tokenWithoutScope, "\"0\"", "resume-key-no-scope", requestBody("ROOT_CAUSE_RESOLVED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectATicketThatIsNotEscalated() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = resume(
            ticketId, supportTokenWithScope("lead.sam"), "\"0\"", "resume-key-not-escalated", requestBody("ROOT_CAUSE_RESOLVED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedEscalatedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, queueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = resume(
            ticketId, supportTokenWithScope("lead.sam"), "\"5\"", "resume-key-stale", requestBody("ROOT_CAUSE_RESOLVED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldReturn404ForAMissingTicket() {
        ResponseEntity<String> response = resume(
            UUID.randomUUID(), supportTokenWithScope("lead.sam"), "\"0\"", "resume-key-missing", requestBody("ROOT_CAUSE_RESOLVED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
