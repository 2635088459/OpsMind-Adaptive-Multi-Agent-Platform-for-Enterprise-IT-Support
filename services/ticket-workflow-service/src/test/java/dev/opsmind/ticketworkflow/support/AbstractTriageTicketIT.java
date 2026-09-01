package dev.opsmind.ticketworkflow.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for Triage Ticket (SPEC-TW-007) PostgreSQL integration
 * tests: full Spring context, real PostgreSQL Testcontainer, real signed
 * JWTs, and direct SQL seeding of the parent Ticket plus the new catalog
 * tables (Triage itself is always exercised through the real HTTP
 * endpoint). Mirrors {@code AbstractAddTicketMessageIT}'s structure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractTriageTicketIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    protected static final String DEFAULT_TEAM_ID = "TEAM-HOUSING";
    protected static final String TRIAGE_SCOPE = "ticket:triage";
    protected static final String DEFAULT_REASON = "VPN access failure affects the requester's scheduled shift.";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * {@code ticket.tickets} CASCADE also removes its dependent status
     * history/audit(no FK, truncated separately)/outbox/message/resolution
     * cycle rows. The catalog tables (categories, subcategories, support
     * queues) have no dependents flowing the other way, so they are listed
     * explicitly in the same statement; Postgres computes the full CASCADE
     * closure across every table named regardless of order.
     */
    @BeforeEach
    void resetTriageData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets, ticket.ticket_categories, ticket.support_queues CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.idempotency_records");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.audit_records");
    }

    protected UUID seedOpenTicket() {
        return seedOpenTicket(UUID.randomUUID());
    }

    protected UUID seedOpenTicket(UUID ticketId) {
        UUID resolutionCycleId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, 'UNASSIGNED', 'NEW', ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, resolutionCycleId,
            Timestamp.from(now), Timestamp.from(now), DEFAULT_REQUESTER
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(now)
        );

        return ticketId;
    }

    /** Seeds a ticket already past triage (e.g. {@code TRIAGED} or another non-{@code NEW} status) for state-guard tests. */
    protected UUID seedTicketInStatus(UUID ticketId, String status) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        String displayId = "INC-" + Math.abs(ticketId.hashCode());
        UUID resolutionCycleId = UUID.randomUUID();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        // REAL BUG found live (SPEC-OP-036-era investigation, 2026-09-01): V016/
        // V017/V015 later tightened ck_tickets_resolved_fields/
        // ck_tickets_closed_fields/ck_tickets_waiting_for_user_metadata beyond
        // what this helper originally set (see AbstractAddTicketMessageIT's own
        // fix comment for the full history) — RESOLVED here additionally needs
        // resolved_by/resolution_code/resolution_summary/current_support_user_id.
        boolean resolved = "RESOLVED".equals(status);
        boolean closedStatus = "CLOSED".equals(status);
        boolean waitingForUser = "WAITING_FOR_USER".equals(status);
        // ck_tickets_support_user_requires_team (V002): current_support_user_id
        // requires current_team_id too -- another real, previously-masked
        // constraint (only surfaced once the missing-column bug above was fixed).
        String supportUserId = (resolved || closedStatus || waitingForUser) ? "support-1" : null;
        String supportTeamId = supportUserId != null ? DEFAULT_TEAM_ID : null;
        Timestamp resolvedAt = (resolved || closedStatus) ? Timestamp.from(now) : null;
        Timestamp autoCloseDueAt = resolved ? Timestamp.from(now.plusSeconds(3600)) : null;
        String resolvedBy = (resolved || closedStatus) ? supportUserId : null;
        String resolutionCode = (resolved || closedStatus) ? "FIXED" : null;
        String resolutionSummary = (resolved || closedStatus) ? "Requester's VPN client re-enrolled successfully." : null;
        Timestamp closedAt = closedStatus ? Timestamp.from(now) : null;
        String closeReasonCode = closedStatus ? "SUPPORT_CONFIRMED" : null;
        String closedBy = closedStatus ? supportUserId : null;
        Timestamp cancelledAt = "CANCELLED".equals(status) ? Timestamp.from(now) : null;
        String cancelReasonCode = "CANCELLED".equals(status) ? "REQUESTER_CANCELLED" : null;
        Timestamp waitingForRequesterSince = waitingForUser ? Timestamp.from(now) : null;
        boolean triaged = !"NEW".equals(status);

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at,
                 priority, status, current_team_id, current_support_user_id, current_resolution_cycle_id, resolved_at,
                 auto_close_due_at, resolved_by, resolution_code, resolution_summary, closed_at,
                 close_reason_code, closed_by, cancelled_at, cancel_reason_code,
                 waiting_for_requester_since, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, 'HIGH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE,
            triaged ? categoryId : null, triaged ? queueId : null, triaged ? DEFAULT_REQUESTER : null, triaged ? Timestamp.from(now) : null,
            status, supportTeamId, supportUserId, resolutionCycleId,
            resolvedAt, autoCloseDueAt, resolvedBy, resolutionCode, resolutionSummary,
            closedAt, closeReasonCode, closedBy, cancelledAt, cancelReasonCode, waitingForRequesterSince,
            Timestamp.from(now), Timestamp.from(now), DEFAULT_REQUESTER
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(now)
        );

        return ticketId;
    }

    protected UUID seedCategory(boolean active) {
        return seedCategory(UUID.randomUUID(), active);
    }

    protected UUID seedCategory(UUID categoryId, boolean active) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_categories (category_id, code, display_name, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            categoryId, "CODE-" + categoryId, "Category " + categoryId, active, Timestamp.from(now), Timestamp.from(now)
        );
        return categoryId;
    }

    protected UUID seedSubcategory(UUID categoryId, boolean active) {
        return seedSubcategory(UUID.randomUUID(), categoryId, active);
    }

    protected UUID seedSubcategory(UUID subcategoryId, UUID categoryId, boolean active) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_subcategories (subcategory_id, category_id, code, display_name, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            subcategoryId, categoryId, "SUBCODE-" + subcategoryId, "Subcategory " + subcategoryId, active, Timestamp.from(now), Timestamp.from(now)
        );
        return subcategoryId;
    }

    protected UUID seedSupportQueue(String teamId, boolean active) {
        return seedSupportQueue(UUID.randomUUID(), teamId, active);
    }

    protected UUID seedSupportQueue(UUID supportQueueId, String teamId, boolean active) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        jdbcTemplate.update("""
            INSERT INTO ticket.support_queues (support_queue_id, team_id, display_name, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            supportQueueId, teamId, "Queue for " + teamId, active, Timestamp.from(now), Timestamp.from(now)
        );
        return supportQueueId;
    }

    protected String supportToken(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(TRIAGE_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    protected String supportTokenWithoutTriageScope(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    protected String employeeToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "employee-portal", Set.of(),
            Map.of("actor_type", "EMPLOYEE")
        );
    }

    protected String triageRequestBody(UUID categoryId, UUID subcategoryId, String priority, UUID supportQueueId) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"categoryId\":\"").append(categoryId).append("\",");
        if (subcategoryId != null) {
            json.append("\"subcategoryId\":\"").append(subcategoryId).append("\",");
        }
        json.append("\"priority\":\"").append(priority).append("\",");
        json.append("\"supportQueueId\":\"").append(supportQueueId).append("\",");
        json.append("\"reason\":\"").append(DEFAULT_REASON).append("\"");
        json.append("}");
        return json.toString();
    }

    protected ResponseEntity<String> triage(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
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
        return restTemplate.exchange("/api/v1/tickets/" + ticketId + "/triage", HttpMethod.POST, entity, String.class);
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> ticketRow(UUID ticketId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ticket.tickets WHERE ticket_id = ?", ticketId);
    }
}
