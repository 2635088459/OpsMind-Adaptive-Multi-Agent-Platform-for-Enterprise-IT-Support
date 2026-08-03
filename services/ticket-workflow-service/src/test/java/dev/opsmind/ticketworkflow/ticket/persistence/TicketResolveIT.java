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
 * SPEC-TW-010 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketAssignmentSuccessIT}'s shape. Reuses {@link AbstractTicketAssignmentIT}'s
 * seeding helpers ({@code seedAssignedTicket} already supports an {@code
 * IN_PROGRESS} status ticket with an active resolution cycle) rather than
 * duplicating fixture plumbing for a single spec's IT suite.
 */
@Tag("integration")
class TicketResolveIT extends AbstractTicketAssignmentIT {

    private static final String RESOLVE_SCOPE = "ticket:resolve";
    private static final String SUMMARY = "Reinstalled the endpoint management profile and confirmed the device checked in successfully.";

    private String resolveToken(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(RESOLVE_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    private ResponseEntity<String> resolve(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/resolution", HttpMethod.POST, entity, String.class);
    }

    private String resolveBody(String code, String summary) {
        return "{\"resolutionCode\":\"" + code + "\",\"resolutionSummary\":\"" + summary + "\"}";
    }

    private Map<String, Object> resolutionCycleRow(UUID ticketId) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE ticket_id = ? ORDER BY cycle_number DESC LIMIT 1", ticketId
        );
    }

    @Test
    void shouldResolveAnInProgressTicketAndCompleteItsResolutionCycle() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = resolve(
            ticketId, resolveToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "resolve-key-1", resolveBody("FIXED", SUMMARY)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody()).contains("\"status\":\"RESOLVED\"").contains("\"resolutionCode\":\"FIXED\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("RESOLVED");
        assertThat(ticketRow.get("resolution_code")).isEqualTo("FIXED");
        assertThat(ticketRow.get("resolution_summary")).isEqualTo(SUMMARY);
        assertThat(ticketRow.get("resolved_by")).isEqualTo("sam.support");
        assertThat(ticketRow.get("resolved_at")).isNotNull();
        assertThat(ticketRow.get("auto_close_due_at")).isNotNull();
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(ticketRow.get("waiting_for_requester_since")).isNull();
        assertThat(ticketRow.get("approval_reference")).isNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> cycleRow = resolutionCycleRow(ticketId);
        assertThat(cycleRow.get("cycle_status")).isEqualTo("RESOLVED");
        assertThat(cycleRow.get("resolution_code")).isEqualTo("FIXED");
        assertThat(cycleRow.get("resolution_summary")).isEqualTo(SUMMARY);
        assertThat(cycleRow.get("resolved_by_id")).isEqualTo("sam.support");
        assertThat(cycleRow.get("resolved_at")).isNotNull();

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-010'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolved'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.audit_records WHERE resource_id = ? AND action = 'TICKET_RESOLVED'", Integer.class, ticketId.toString()
        );
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        String token = resolveToken("sam.support", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = resolve(ticketId, token, "\"0\"", "resolve-key-replay", resolveBody("FIXED", SUMMARY));
        ResponseEntity<String> replay = resolve(ticketId, token, "\"0\"", "resolve-key-replay", resolveBody("FIXED", SUMMARY));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolved'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAStaleVersionWithNoWrites() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = resolve(
            ticketId, resolveToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"5\"", "resolve-key-stale", resolveBody("FIXED", SUMMARY)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAnAssignedTicketNotYetInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = resolve(
            ticketId, resolveToken("sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "resolve-key-invalid-state", resolveBody("FIXED", SUMMARY)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("ASSIGNED");

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(0);
    }

    @Test
    void shouldRejectAnActorOutsideTheTicketsQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = resolve(
            ticketId, resolveToken("sam.support", Set.of("SOME-OTHER-TEAM")), "\"0\"", "resolve-key-forbidden", resolveBody("FIXED", SUMMARY)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("QUEUE_ACCESS_DENIED");
    }
}
