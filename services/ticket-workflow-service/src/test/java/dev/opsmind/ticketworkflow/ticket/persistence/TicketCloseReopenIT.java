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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-011 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketResolveIT}'s
 * shape. Seeds its own {@code RESOLVED}/{@code CLOSED} tickets (with a
 * matching resolution-cycle snapshot) directly, since neither status is
 * covered by {@link AbstractTicketAssignmentIT}'s own seeding helpers.
 */
@Tag("integration")
class TicketCloseReopenIT extends AbstractTicketAssignmentIT {

    private static final String CLOSE_SCOPE = "ticket:close";
    private static final String REOPEN_SCOPE = "ticket:reopen";
    private static final String CLOSE_REASON = "Requester confirmed the issue is resolved and no further action is required.";
    private static final String REOPEN_REASON = "The requester reported the same endpoint enrollment failure returned after reboot.";

    private String tokenWithScope(String scope, String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(scope),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    private ResponseEntity<String> post(UUID ticketId, String route, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/" + route, HttpMethod.POST, entity, String.class);
    }

    private ResponseEntity<String> close(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        return post(ticketId, "closure", bearerToken, ifMatch, idempotencyKey, body);
    }

    private ResponseEntity<String> reopen(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        return post(ticketId, "reopen", bearerToken, ifMatch, idempotencyKey, body);
    }

    private String closeBody(String code, String reason) {
        return "{\"closeReasonCode\":\"" + code + "\",\"closeReason\":\"" + reason + "\"}";
    }

    private String reopenBody(String code, String reason) {
        return "{\"reopenReasonCode\":\"" + code + "\",\"reopenReason\":\"" + reason + "\"}";
    }

    private Map<String, Object> resolutionCycleRow(UUID ticketId, int cycleNumber) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_resolution_cycles WHERE ticket_id = ? AND cycle_number = ?", ticketId, cycleNumber
        );
    }

    /** A {@code RESOLVED} ticket with its cycle already {@code RESOLVED} — ready for {@code /closure}. */
    private UUID seedResolvedTicketReadyToClose(String teamId, UUID supportQueueId, String assigneeId) {
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

    /** A {@code CLOSED} ticket with its cycle already {@code CLOSED} — ready for {@code /reopen}. */
    private UUID seedClosedTicketReadyToReopen(String teamId, UUID supportQueueId, String assigneeId) {
        UUID ticketId = UUID.randomUUID();
        UUID categoryId = seedCategory(true);
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at, current_team_id, current_support_user_id,
                 assigned_at, assigned_by, priority, status, resolved_at, resolved_by, resolution_code, resolution_summary,
                 closed_at, closed_by, close_reason_code,
                 current_resolution_cycle_id, created_at, updated_at, version, created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', 'CLOSED', ?, ?, 'FIXED', ?, ?, ?, 'REQUESTER_CONFIRMED',
                    ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, categoryId, supportQueueId, "support-000", Timestamp.from(SEED_NOW), teamId, assigneeId,
            Timestamp.from(SEED_NOW), "support-000",
            Timestamp.from(SEED_NOW), assigneeId,
            "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
            Timestamp.from(SEED_NOW.plusSeconds(3600)), assigneeId,
            resolutionCycleId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at,
                 resolved_at, resolution_code, resolution_summary, resolved_by_type, resolved_by_id,
                 closed_at, closed_by_type, closed_by_id, close_reason_code)
            VALUES (?, ?, 1, 'CLOSED', ?, ?, 'FIXED', ?, 'IT_SUPPORT', ?, ?, 'IT_SUPPORT', ?, 'REQUESTER_CONFIRMED')
            """,
            resolutionCycleId, ticketId, Timestamp.from(SEED_NOW),
            Timestamp.from(SEED_NOW), "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
            assigneeId, Timestamp.from(SEED_NOW.plusSeconds(3600)), assigneeId
        );

        return ticketId;
    }

    @Test
    void shouldCloseAResolvedTicketAndCompleteItsResolutionCycle() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToClose(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = close(
            ticketId, tokenWithScope(CLOSE_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "close-key-1",
            closeBody("REQUESTER_CONFIRMED", CLOSE_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody()).contains("\"status\":\"CLOSED\"").contains("\"closeReasonCode\":\"REQUESTER_CONFIRMED\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("CLOSED");
        assertThat(ticketRow.get("closed_by")).isEqualTo("sam.support");
        assertThat(ticketRow.get("close_reason_code")).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(ticketRow.get("closed_at")).isNotNull();
        assertThat(ticketRow.get("active_workflow_id")).isNull();
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> cycleRow = resolutionCycleRow(ticketId, 1);
        assertThat(cycleRow.get("cycle_status")).isEqualTo("CLOSED");
        assertThat(cycleRow.get("close_reason_code")).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(cycleRow.get("closed_by_id")).isEqualTo("sam.support");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-011'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.closed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReopenAClosedTicketWithANewActiveResolutionCycle() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedClosedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> response = reopen(
            ticketId, tokenWithScope(REOPEN_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "reopen-key-1",
            reopenBody("ISSUE_RECURRED", REOPEN_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"IN_PROGRESS\"")
            .contains("\"reopenCount\":1")
            .contains("\"ownershipStatus\":\"ACTIVE\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(ticketRow.get("resolved_at")).isNull();
        assertThat(ticketRow.get("resolution_code")).isNull();
        assertThat(ticketRow.get("resolution_summary")).isNull();
        assertThat(ticketRow.get("closed_at")).isNull();
        assertThat(ticketRow.get("closed_by")).isNull();
        assertThat(ticketRow.get("close_reason_code")).isNull();
        assertThat(((Number) ticketRow.get("reopen_count")).intValue()).isEqualTo(1);
        assertThat(ticketRow.get("last_reopen_reason_code")).isEqualTo("ISSUE_RECURRED");
        assertThat(ticketRow.get("current_support_user_id")).isEqualTo(DEFAULT_ASSIGNEE_ID);
        UUID newCycleId = (UUID) ticketRow.get("current_resolution_cycle_id");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> oldCycleRow = resolutionCycleRow(ticketId, 1);
        assertThat(oldCycleRow.get("cycle_status")).isEqualTo("REOPENED");
        assertThat(oldCycleRow.get("reopen_reason_code")).isEqualTo("ISSUE_RECURRED");
        assertThat(oldCycleRow.get("resolution_code")).isEqualTo("FIXED");

        Map<String, Object> newCycleRow = resolutionCycleRow(ticketId, 2);
        assertThat(newCycleRow.get("resolution_cycle_id")).isEqualTo(newCycleId);
        assertThat(newCycleRow.get("cycle_status")).isEqualTo("ACTIVE");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-013'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.reopened'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldReopenAResolvedTicketDirectlyWithoutRequiringClosureFirst() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToClose(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = reopen(
            ticketId, tokenWithScope(REOPEN_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "reopen-key-2",
            reopenBody("RESOLUTION_FAILED", REOPEN_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"previousStatus\":\"RESOLVED\"");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-012'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    void shouldReportAssigneeInactiveOwnershipStatusOnReopenWithoutBlockingIt() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedClosedTicketReadyToReopen(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", false);

        ResponseEntity<String> response = reopen(
            ticketId, tokenWithScope(REOPEN_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "reopen-key-3",
            reopenBody("ISSUE_RECURRED", REOPEN_REASON)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"ownershipStatus\":\"ASSIGNEE_INACTIVE\"");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldReplayAnIdenticalCloseRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToClose(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        String token = tokenWithScope(CLOSE_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID));

        ResponseEntity<String> first = close(ticketId, token, "\"0\"", "close-key-replay", closeBody("REQUESTER_CONFIRMED", CLOSE_REASON));
        ResponseEntity<String> replay = close(ticketId, token, "\"0\"", "close-key-replay", closeBody("REQUESTER_CONFIRMED", CLOSE_REASON));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.closed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldRejectClosingATicketThatIsNotYetResolved() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = close(
            ticketId, tokenWithScope(CLOSE_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "close-key-invalid-state",
            closeBody("REQUESTER_CONFIRMED", CLOSE_REASON)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAStaleVersionOnClose() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToClose(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = close(
            ticketId, tokenWithScope(CLOSE_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"5\"", "close-key-stale",
            closeBody("REQUESTER_CONFIRMED", CLOSE_REASON)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    void shouldSupportTheFullLifecycleLoopResolveCloseReopen() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        seedSupportAgent(DEFAULT_ASSIGNEE_ID, "IT_SUPPORT", true);

        ResponseEntity<String> resolveResponse = post(
            ticketId, "resolution", tokenWithScope("ticket:resolve", "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"0\"", "lifecycle-resolve",
            "{\"resolutionCode\":\"FIXED\",\"resolutionSummary\":\"" + "Reinstalled the endpoint management profile and confirmed the device checked in successfully." + "\"}"
        );
        assertThat(resolveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> closeResponse = close(
            ticketId, tokenWithScope(CLOSE_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"1\"", "lifecycle-close",
            closeBody("REQUESTER_CONFIRMED", CLOSE_REASON)
        );
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("CLOSED");

        ResponseEntity<String> reopenResponse = reopen(
            ticketId, tokenWithScope(REOPEN_SCOPE, "sam.support", Set.of(DEFAULT_TEAM_ID)), "\"2\"", "lifecycle-reopen",
            reopenBody("ISSUE_RECURRED", REOPEN_REASON)
        );
        assertThat(reopenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> finalRow = ticketRow(ticketId);
        assertThat(finalRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) finalRow.get("version")).longValue()).isEqualTo(3L);
        assertThat(((Number) finalRow.get("reopen_count")).intValue()).isEqualTo(1);

        Integer statusHistoryCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(statusHistoryCount).isEqualTo(3);

        Integer cycleCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_resolution_cycles WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(cycleCount).isEqualTo(2);
    }
}
