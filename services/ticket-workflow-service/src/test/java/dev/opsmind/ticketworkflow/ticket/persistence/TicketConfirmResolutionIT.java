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
 * SPEC-TW-026 persistence/E2E: full Spring context + real PostgreSQL
 * Testcontainer + real signed JWTs, mirroring {@code TicketCloseReopenIT}'s
 * (SPEC-TW-011) shape. Seeds its own {@code RESOLVED} ticket with a matching
 * {@code RESOLVED} resolution-cycle snapshot directly, since neither is
 * covered by {@link AbstractTicketAssignmentIT}'s own seeding helpers.
 */
@Tag("integration")
class TicketConfirmResolutionIT extends AbstractTicketAssignmentIT {

    private static final String REQUIRED_SCOPE = "ticket:resolution-confirm";
    private static final String REASON = "Requester confirmed the issue is resolved and no further action is required.";

    private String employeeTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "employee-portal", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "EMPLOYEE"));
    }

    private String supportTokenWithScope(String subject) {
        return TestJwtSupport.mintToken(subject, "support-console", Set.of(REQUIRED_SCOPE), Map.of("actor_type", "IT_SUPPORT"));
    }

    private ResponseEntity<String> confirmResolution(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/resolution-confirmation", HttpMethod.POST, entity, String.class);
    }

    private String requestBody(String reasonCode) {
        return "{\"reasonCode\":\"" + reasonCode + "\",\"reason\":\"" + REASON + "\"}";
    }

    /** A {@code RESOLVED} ticket, owned by {@link #DEFAULT_REQUESTER}, with its cycle already {@code RESOLVED} — ready for confirmation. */
    private UUID seedResolvedTicketReadyToConfirm(String teamId, UUID supportQueueId, String assigneeId) {
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
    void shouldConfirmResolutionAsTheTicketsOwnRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = confirmResolution(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "confirm-key-1", requestBody("REQUESTER_CONFIRMED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody())
            .contains("\"status\":\"CLOSED\"")
            .contains("\"previousStatus\":\"RESOLVED\"")
            .contains("\"reasonCode\":\"REQUESTER_CONFIRMED\"");

        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("CLOSED");
        assertThat(ticketRow.get("close_reason_code")).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);

        Map<String, Object> cycleRow = resolutionCycleRow(ticketId, 1);
        assertThat(cycleRow.get("cycle_status")).isEqualTo("CLOSED");
        assertThat(cycleRow.get("close_reason_code")).isEqualTo("REQUESTER_CONFIRMED");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-031'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolution-confirmed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldConfirmResolutionAsAnAuthorizedSupportActorEvenWithoutQueueMembership() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = confirmResolution(
            ticketId, supportTokenWithScope("sam.support"), "\"0\"", "confirm-key-support", requestBody("SUPPORT_CONFIRMED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"reasonCode\":\"SUPPORT_CONFIRMED\"");
    }

    @Test
    void shouldReplayAnIdenticalRetryWithoutDuplicateSideEffects() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        String token = employeeTokenWithScope(DEFAULT_REQUESTER);

        ResponseEntity<String> first = confirmResolution(ticketId, token, "\"0\"", "confirm-key-replay", requestBody("REQUESTER_CONFIRMED"));
        ResponseEntity<String> replay = confirmResolution(ticketId, token, "\"0\"", "confirm-key-replay", requestBody("REQUESTER_CONFIRMED"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.resolution-confirmed'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
    }

    @Test
    void shouldRejectAnEmployeeWhoIsNotTheTicketsRequester() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = confirmResolution(
            ticketId, employeeTokenWithScope("someone-else"), "\"0\"", "confirm-key-forbidden", requestBody("REQUESTER_CONFIRMED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("RESOLVED");
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);
        String tokenWithoutScope = TestJwtSupport.mintToken(DEFAULT_REQUESTER, "employee-portal", Set.of(), Map.of("actor_type", "EMPLOYEE"));

        ResponseEntity<String> response = confirmResolution(
            ticketId, tokenWithoutScope, "\"0\"", "confirm-key-no-scope", requestBody("REQUESTER_CONFIRMED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }

    @Test
    void shouldRejectATicketNotYetResolved() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);

        ResponseEntity<String> response = confirmResolution(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"0\"", "confirm-key-invalid-state", requestBody("REQUESTER_CONFIRMED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_STATUS_TRANSITION");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRejectAStaleVersion() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedResolvedTicketReadyToConfirm(DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID);

        ResponseEntity<String> response = confirmResolution(
            ticketId, employeeTokenWithScope(DEFAULT_REQUESTER), "\"5\"", "confirm-key-stale", requestBody("REQUESTER_CONFIRMED")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }
}
