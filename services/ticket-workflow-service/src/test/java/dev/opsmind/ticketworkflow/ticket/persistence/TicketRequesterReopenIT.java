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
 * SPEC-TW-028 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code
 * TicketConfirmResolutionIT}'s (SPEC-TW-026) shape. Seeds its own {@code
 * RESOLVED}/{@code CLOSED} tickets (with a matching resolution-cycle
 * snapshot) directly, mirroring {@code TicketCloseReopenIT}'s (SPEC-TW-011)
 * own seeding helpers.
 */
@Tag("integration")
class TicketRequesterReopenIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:reopen-request";
    private static final String REASON = "The requester reported the same endpoint enrollment failure returned after reboot.";

    private String employeeTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "employee-portal", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "EMPLOYEE"));
    }

    private String supportTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT"));
    }

    private ResponseEntity<String> reopen(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/reopen-request", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String reasonCode) {
        return "{\"reopenReasonCode\":\"" + reasonCode + "\",\"reopenReason\":\"" + REASON + "\"}";
    }

    /** A {@code RESOLVED} ticket, owned by {@link #DEFAULT_REQUESTER}, with its cycle already {@code RESOLVED} — ready to reopen. */
    private UUID seedResolvedTicketReadyToReopen(String teamId, UUID supportQueueId, String assigneeId) {
        UUID ticketId = UUID.randomUUID();
        UUID categoryId = seedCategory(true);
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at, current_team_id, current_support_user_id,
                 assigned_at, assigned_by, priority, status, resolved_at, auto_close_due_at, resolved_by,
                 resolution_code, resolution_summary, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', 'RESOLVED', ?, ?, ?, 'FIXED', ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, categoryId, supportQueueId, "support-000", Timestamp.from(SEED_NOW), teamId, assigneeId,
            Timestamp.from(SEED_NOW), "support-000",
            Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW.plusSeconds(604_800)), assigneeId,
            "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
            resolutionCycleId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at,
                 resolved_at, resolution_code, resolution_summary, resolved_by_type, resolved_by_id)
            VALUES (?, ?, 1, 'RESOLVED', ?, ?, 'FIXED', ?, 'IT_SUPPORT', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW),
            "Reinstalled the endpoint management profile and confirmed the device checked in successfully.", assigneeId
        );

        return ticketId;
    }

    private Map<String, Object> resolutionCycleRow(UUID ticketId, int cycleNumber) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE ticket_id = ? AND cycle_number = ?", ticketId, cycleNumber
        );
    }

    @Test
    void shouldReopenAResolvedTicketAsTheTicketsOwnRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> response = reopen(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "reopen-request-key-1", requestBody("REQUESTER_REPORTED_NOT_FIXED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"IN_PROGRESS\"")
            .contains("\"previousStatus\":\"RESOLVED\"")
            .contains("\"reopenCount\":1")
            .contains("\"ownershipStatus\":\"ACTIVE\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(ticketRow.get("reopen_count")).isEqualTo(1);
        assertThat(ticketRow.get("last_reopen_reason_code")).isEqualTo("REQUESTER_REPORTED_NOT_FIXED");
        assertThat(ticketRow.get("resolved_at")).isNull();
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        UUID newResolutionCycleId = (UUID) ticketRow.get("current_resolution_cycle_id");
        Map<String, Object> newCycleRow = resolutionCycleRow(ticketId, 2);
        assertThat(newCycleRow.get("resolution_cycle_id")).isEqualTo(newResolutionCycleId);
        assertThat(newCycleRow.get("cycle_status")).isEqualTo("ACTIVE");

        Map<String, Object> oldCycleRow = resolutionCycleRow(ticketId, 1);
        assertThat(oldCycleRow.get("cycle_status")).isEqualTo("REOPENED");
        assertThat(oldCycleRow.get("reopen_reason_code")).isEqualTo("REQUESTER_REPORTED_NOT_FIXED");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-012'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.reopened'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReopenAsAnAuthorizedSupportActorEvenWithoutQueueMembership() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> response = reopen(
            ticketId, supportTokenWithScope("sam.support"), "\"0\"", "reopen-request-key-support", requestBody("SUPPORT_REVIEW_REQUIRED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"reopenReasonCode\":\"SUPPORT_REVIEW_REQUIRED\"");
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);
        String token = employeeTokenWithScope(DEFAULT_REQUESTER);

        ResponseEntity<String> first = reopen(ticketId, token, "\"0\"", "reopen-request-key-replay", requestBody("REQUESTER_REPORTED_NOT_FIXED"));
        ResponseEntity<String> replay = reopen(ticketId, token, "\"0\"", "reopen-request-key-replay", requestBody("REQUESTER_REPORTED_NOT_FIXED"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.reopened'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = reopen(
            ticketId, employeeTokenWithScope("someone-else"), "\"0\"", "reopen-request-key-forbidden", requestBody("REQUESTER_REPORTED_NOT_FIXED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("RESOLVED");
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        String tokenWithoutScope = TestJwtSupport.mintToken(DEFAULT_REQUESTER, "employee-portal", Set.of(), Map.of("actor_type", "EMPLOYEE"));

        ResponseEntity<String> response = reopen(
            ticketId, tokenWithoutScope, "\"0\"", "reopen-request-key-no-scope", requestBody("REQUESTER_REPORTED_NOT_FIXED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectATicketThatIsNotResolvedOrClosed() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = reopen(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "reopen-request-key-invalid-state", requestBody("REQUESTER_REPORTED_NOT_FIXED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = reopen(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"5\"", "reopen-request-key-stale", requestBody("REQUESTER_REPORTED_NOT_FIXED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }
}
