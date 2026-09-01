package dev.opsmind.ticketworkflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for Support Queue PostgreSQL integration tests: full
 * Spring context, real PostgreSQL Testcontainer, real signed JWTs, and
 * direct SQL seeding of Tickets plus their SLA cycle (needed for SLA-rank
 * and filter scenarios) without going through the Create Ticket command
 * flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractSupportQueueIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    protected static final String DEFAULT_TEAM = "TEAM-HOUSING";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The Queue query returns every authorized row, so leftover rows from
     * an earlier {@code @Test} method sharing one container/database
     * would silently contaminate a later test's result set. Truncating
     * {@code tickets} (cascading to resolution cycles, SLA cycles, status
     * history, and outbox events via FK) before each test keeps every
     * test's seed data isolated.
     */
    @BeforeEach
    void resetTicketData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets CASCADE");
    }

    protected UUID seedTicket(String applicationCode, String status, Instant createdAt) {
        return seedTicket(UUID.randomUUID(), DEFAULT_REQUESTER, applicationCode, status, "MEDIUM", DEFAULT_TEAM, null, createdAt, "ACTIVE", createdAt.plusSeconds(86400));
    }

    protected UUID seedTicket(String applicationCode, String status, String priority, Instant createdAt) {
        return seedTicket(UUID.randomUUID(), DEFAULT_REQUESTER, applicationCode, status, priority, DEFAULT_TEAM, null, createdAt, "ACTIVE", createdAt.plusSeconds(86400));
    }

    protected UUID seedTicket(
        UUID ticketId,
        String requesterId,
        String applicationCode,
        String status,
        String priority,
        String teamId,
        String agentId,
        Instant createdAt,
        String slaStatus,
        Instant resolutionDueAt
    ) {
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        // RESOLVED/CLOSED/CANCELLED/WAITING_FOR_USER each require extra fields to
        // satisfy ticket.tickets' status-specific CHECK constraints. This comment
        // originally cited only V002's ck_tickets_resolved_fields/
        // ck_tickets_closed_fields (resolved_at/auto_close_due_at/closed_at/
        // close_reason_code) — REAL BUG found live (SPEC-OP-036-era investigation,
        // 2026-09-01): V016/V017 later tightened those constraints to also
        // require resolved_by/resolution_code/resolution_summary/
        // current_support_user_id (RESOLVED) and closed_by (CLOSED) — a caller
        // passing agentId=null for a RESOLVED ticket (a real, valid scenario:
        // "resolved but not currently assigned to anyone specific") failed with a
        // real DataIntegrityViolationException. Defaulting a real agent id and
        // the resolution/close metadata below when the caller didn't supply one
        // for a status that now requires it.
        boolean resolved = "RESOLVED".equals(status);
        boolean closed = "CLOSED".equals(status);
        boolean waitingForUser = "WAITING_FOR_USER".equals(status);
        String effectiveAgentId = ((resolved || closed || waitingForUser) && agentId == null) ? "support-1" : agentId;
        Timestamp resolvedAt = (resolved || closed) ? Timestamp.from(createdAt) : null;
        Timestamp autoCloseDueAt = resolved ? Timestamp.from(createdAt.plusSeconds(3600)) : null;
        String resolvedBy = (resolved || closed) ? effectiveAgentId : null;
        String resolutionCode = (resolved || closed) ? "FIXED" : null;
        String resolutionSummary = (resolved || closed) ? "Requester's VPN client re-enrolled successfully." : null;
        Timestamp closedAt = closed ? Timestamp.from(createdAt) : null;
        String closeReasonCode = closed ? "SUPPORT_CONFIRMED" : null;
        String closedBy = closed ? effectiveAgentId : null;
        Timestamp cancelledAt = "CANCELLED".equals(status) ? Timestamp.from(createdAt) : null;
        String cancelReasonCode = "CANCELLED".equals(status) ? "REQUESTER_CANCELLED" : null;
        Timestamp waitingForRequesterSince = waitingForUser ? Timestamp.from(createdAt) : null;

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_team_id, current_support_user_id, current_resolution_cycle_id,
                 resolved_at, auto_close_due_at, resolved_by, resolution_code, resolution_summary, closed_at,
                 close_reason_code, closed_by, cancelled_at, cancel_reason_code, waiting_for_requester_since,
                 created_at, updated_at, version, created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, requesterId,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            applicationCode, priority, status, teamId, effectiveAgentId, resolutionCycleId,
            resolvedAt, autoCloseDueAt, resolvedBy, resolutionCode, resolutionSummary,
            closedAt, closeReasonCode, closedBy, cancelledAt, cancelReasonCode, waitingForRequesterSince,
            Timestamp.from(createdAt), Timestamp.from(createdAt), requesterId
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(createdAt)
        );

        if (slaStatus != null) {
            jdbcTemplate.update("""
                INSERT INTO ticket.ticket_sla_cycles
                    (sla_cycle_id, ticket_id, resolution_cycle_id, policy_id, cycle_number, status,
                     response_due_at, resolution_due_at, created_at, updated_at, version)
                VALUES (?, ?, ?, 'DEFAULT', 1, ?, ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), ticketId, resolutionCycleId, slaStatus,
                Timestamp.from(createdAt.plusSeconds(3600)), resolutionDueAt == null ? null : Timestamp.from(resolutionDueAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt)
            );
        }

        return ticketId;
    }

    protected String supportToken(String subject, Set<String> allowedApplicationCodes, Set<String> allowedTeams) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(SupportQueueFixtures.QUEUE_READ_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_queues", List.copyOf(allowedApplicationCodes), "support_teams", List.copyOf(allowedTeams))
        );
    }

    protected String supportToken(String subject, String actorType, Set<String> allowedApplicationCodes, Set<String> allowedTeams) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(SupportQueueFixtures.QUEUE_READ_SCOPE),
            Map.of("actor_type", actorType, "support_queues", List.copyOf(allowedApplicationCodes), "support_teams", List.copyOf(allowedTeams))
        );
    }

    protected ResponseEntity<String> queryQueue(String bearerToken, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/v1/support/tickets");
        queryParams.forEach(builder::queryParam);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(builder.build().toUriString(), HttpMethod.GET, entity, String.class);
    }

    protected JsonNode bodyAsJson(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse response body as JSON", e);
        }
    }

    protected List<UUID> itemTicketIds(JsonNode body) {
        return body.get("items").findValuesAsText("ticketId").stream().map(UUID::fromString).toList();
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
