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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for Add Ticket Message PostgreSQL integration tests: full
 * Spring context, real PostgreSQL Testcontainer, real signed JWTs, and
 * direct SQL seeding of the parent Ticket (Add Message itself is exercised
 * through the real HTTP endpoint, never seeded directly).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractAddTicketMessageIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    protected static final String DEFAULT_CONTENT = "I restarted the VPN client, but the error still appears.";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Each test seeds its own Ticket(s), but a clean slate avoids cross-test
     * row leakage in shared tables. {@code ticket.audit_records} has no
     * foreign key back to {@code ticket.tickets} (it stores resourceId as a
     * loose string, by design, so audit history can outlive its resource),
     * so it is not touched by the CASCADE and must be truncated explicitly.
     */
    @BeforeEach
    void resetTicketData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.idempotency_records");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.audit_records");
    }

    protected UUID seedTicket(String requesterId, String applicationCode, String status) {
        return seedTicket(UUID.randomUUID(), requesterId, applicationCode, status);
    }

    protected UUID seedTicket(UUID ticketId, String requesterId, String applicationCode, String status) {
        UUID resolutionCycleId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        // RESOLVED/CLOSED/CANCELLED each require extra fields to satisfy
        // ticket.tickets' status-specific CHECK constraints (V002).
        Timestamp resolvedAt = ("RESOLVED".equals(status) || "CLOSED".equals(status)) ? Timestamp.from(now) : null;
        Timestamp autoCloseDueAt = "RESOLVED".equals(status) ? Timestamp.from(now.plusSeconds(3600)) : null;
        Timestamp closedAt = "CLOSED".equals(status) ? Timestamp.from(now) : null;
        String closeReasonCode = "CLOSED".equals(status) ? "RESOLVED_CONFIRMED" : null;
        Timestamp cancelledAt = "CANCELLED".equals(status) ? Timestamp.from(now) : null;
        String cancelReasonCode = "CANCELLED".equals(status) ? "REQUESTER_CANCELLED" : null;

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, resolved_at, auto_close_due_at, closed_at,
                 close_reason_code, cancelled_at, cancel_reason_code, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, 'UNASSIGNED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, requesterId,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            applicationCode, status, resolutionCycleId,
            resolvedAt, autoCloseDueAt, closedAt, closeReasonCode, cancelledAt, cancelReasonCode,
            Timestamp.from(now), Timestamp.from(now), requesterId
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

    protected String employeeToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "employee-portal", Set.of("tickets:message:self"), Map.of("actor_type", "EMPLOYEE")
        );
    }

    protected String supportToken(String subject, Set<String> scopes, java.util.List<String> allowedApplicationCodes) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("actor_type", "IT_SUPPORT"));
        if (allowedApplicationCodes != null) {
            claims.put("support_queues", allowedApplicationCodes);
        }
        return TestJwtSupport.mintToken(subject, "support-console", scopes, claims);
    }

    protected ResponseEntity<String> addMessage(UUID ticketId, String bearerToken, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(
            "/api/v1/tickets/" + ticketId + "/messages", HttpMethod.POST, entity, String.class
        );
    }

    protected String employeeBody(String content) {
        return "{\"content\":\"" + content + "\"}";
    }

    protected String supportBody(String content, String messageType) {
        return "{\"content\":\"" + content + "\",\"messageType\":\"" + messageType + "\"}";
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
