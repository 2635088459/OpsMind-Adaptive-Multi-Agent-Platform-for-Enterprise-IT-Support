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
 * SPEC-TW-025 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketStartVerificationIT}'s (SPEC-TW-022) shape. The ticket is seeded
 * directly in {@code VERIFYING} with a {@code SUCCEEDED} {@code
 * ticket_verification_attempts} row (SPEC-TW-022/023) bound to the ticket's
 * current resolution cycle — the trusted evidence this command consumes.
 */
@Tag("integration")
class TicketResolveTicketWithVerificationIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:verified-resolution";

    private String serviceToken(String subject) {
        return TestJwtSupport.mintToken(subject, "verification-orchestrator-service", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "SERVICE"));
    }

    private ResponseEntity<String> resolveWithVerification(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/internal/v1/tickets/" + ticketId + "/verified-resolution", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String verificationEvidenceId) {
        return """
            {"verificationEvidenceId":"%s","resolutionCode":"FIXED",\
            "resolutionSummary":"Verification confirmed the requester can sign in after MFA reset."}\
            """.formatted(verificationEvidenceId);
    }

    private UUID seedVerifyingTicketWithSucceededEvidence(String verificationId, String verificationEvidenceId) {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'VERIFYING' WHERE ticket_id = ?", ticketId);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        seedSucceededAttempt(ticketId, resolutionCycleId, verificationId, verificationEvidenceId, "wf-9000", 1);
        return ticketId;
    }

    private void seedSucceededAttempt(UUID ticketId, UUID resolutionCycleId, String verificationId, String verificationEvidenceId, String workflowId, int attemptNumber) {
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_verification_attempts
                (verification_id, ticket_id, resolution_cycle_id, workflow_id, tool_result_id, attempt_number,
                 attempt_status, verification_type, started_at, verification_evidence_id, completed_at, completed_event_id)
            VALUES (?, ?, ?, ?, 'tool-result-900', ?, 'SUCCEEDED', 'IDENTITY_LOGIN_CHECK', ?, ?, ?, 'evt-completed-1')
            """,
            verificationId, ticketId, resolutionCycleId, workflowId, attemptNumber,
            Timestamp.from(SEED_NOW), verificationEvidenceId, Timestamp.from(SEED_NOW)
        );
    }

    private UUID resolutionCycleId(UUID ticketId) {
        return jdbcTemplate.queryForObject("SELECT current_resolution_cycle_id FROM ticket.tickets WHERE ticket_id = ?", UUID.class, ticketId);
    }

    @Test
    void shouldResolveAVerifyingTicketWithTrustedCurrentVerificationEvidence() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");
        UUID resolutionCycleId = resolutionCycleId(ticketId);

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "resolve-verified-key-1", requestBody("ve-300")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"RESOLVED\"")
            .contains("\"previousStatus\":\"VERIFYING\"")
            .contains("\"verificationId\":\"ver-1234\"")
            .contains("\"verificationEvidenceId\":\"ve-300\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("RESOLVED");
        assertThat(ticketRow.get("verification_evidence_id")).isEqualTo("ve-300");
        assertThat(ticketRow.get("resolution_code")).isEqualTo("FIXED");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> cycleRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE resolution_cycle_id = ?", resolutionCycleId
        );
        assertThat(cycleRow.get("cycle_status")).isEqualTo("RESOLVED");
        assertThat(cycleRow.get("verification_id")).isEqualTo("ver-1234");
        assertThat(cycleRow.get("verification_evidence_id")).isEqualTo("ve-300");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-030'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolved-with-verification'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");
        String token = serviceToken("verification-orchestrator");

        ResponseEntity<String> first = resolveWithVerification(ticketId, token, "\"0\"", "resolve-verified-key-replay", requestBody("ve-300"));
        ResponseEntity<String> replay = resolveWithVerification(ticketId, token, "\"0\"", "resolve-verified-key-replay", requestBody("ve-300"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolved-with-verification'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectATicketNotYetVerifying() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "resolve-verified-key-invalid-state", requestBody("ve-300")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectWhenNoSucceededEvidenceMatchesTheGivenId() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "resolve-verified-key-missing-evidence", requestBody("ve-DOES-NOT-EXIST")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("VERIFICATION_REQUIRED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("VERIFYING");
    }

    @Test
    void shouldRejectEvidenceBoundToAnOldResolutionCycle() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");
        UUID staleCycleId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 2, 'CANCELLED', ?)
            """, staleCycleId, ticketId, Timestamp.from(SEED_NOW));
        seedSucceededAttempt(ticketId, staleCycleId, "ver-old-9999", "ve-STALE", "wf-8000", 1);

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "resolve-verified-key-stale-cycle", requestBody("ve-STALE")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("VERIFICATION_REQUIRED");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("VERIFYING");
    }

    @Test
    void shouldRejectEvidenceThatNeverReachedSucceeded() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_verification_attempts
                (verification_id, ticket_id, resolution_cycle_id, workflow_id, tool_result_id, attempt_number, attempt_status, verification_type, started_at)
            VALUES ('ver-active-2', ?, ?, 'wf-9000', 'tool-result-901', 2, 'ACTIVE', 'IDENTITY_LOGIN_CHECK', ?)
            """, ticketId, resolutionCycleId, Timestamp.from(SEED_NOW));

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"0\"", "resolve-verified-key-not-succeeded", requestBody("ve-NEVER-SUCCEEDED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("VERIFICATION_REQUIRED");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");

        ResponseEntity<String> response = resolveWithVerification(
            ticketId, serviceToken("verification-orchestrator"), "\"5\"", "resolve-verified-key-stale-version", requestBody("ve-300")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldRejectANonServiceActorEvenWithTheRequiredScope() {
        UUID ticketId = seedVerifyingTicketWithSucceededEvidence("ver-1234", "ve-300");
        String humanToken = TestJwtSupport.mintToken(
            "sam.support", "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT")
        );

        ResponseEntity<String> response = resolveWithVerification(ticketId, humanToken, "\"0\"", "resolve-verified-key-forbidden", requestBody("ve-300"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }
}
