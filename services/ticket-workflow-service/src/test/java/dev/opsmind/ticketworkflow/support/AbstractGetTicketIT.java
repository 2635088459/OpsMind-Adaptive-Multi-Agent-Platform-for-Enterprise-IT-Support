package dev.opsmind.ticketworkflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for Get Ticket PostgreSQL integration tests: full Spring
 * context on a random port, real PostgreSQL Testcontainer, real signed
 * JWTs, and direct SQL seeding so each test controls Ticket fields (status,
 * requester, application code) without going through the Create Ticket
 * command flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractGetTicketIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    protected UUID seedTicket() {
        return seedTicket(UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);
    }

    protected UUID seedTicket(String requesterId, String applicationCode) {
        return seedTicket(UUID.randomUUID(), requesterId, applicationCode);
    }

    protected UUID seedTicket(UUID ticketId, String requesterId, String applicationCode) {
        UUID resolutionCycleId = UUID.randomUUID();
        UUID slaCycleId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, 'UNASSIGNED', 'NEW', ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, requesterId,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            applicationCode, resolutionCycleId, Timestamp.from(now), Timestamp.from(now), requesterId
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(now)
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_sla_cycles
                (sla_cycle_id, ticket_id, resolution_cycle_id, policy_id, cycle_number, status,
                 response_due_at, resolution_due_at, created_at, updated_at, version)
            VALUES (?, ?, ?, 'SLA-STANDARD-P2', 1, 'ACTIVE', ?, ?, ?, ?, 0)
            """,
            slaCycleId, ticketId, resolutionCycleId,
            Timestamp.from(now.plusSeconds(4 * 3600)), Timestamp.from(now.plusSeconds(24 * 3600)),
            Timestamp.from(now), Timestamp.from(now)
        );

        return ticketId;
    }

    protected String employeeToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "employee-portal", Set.of("tickets:read:self"), Map.of("actor_type", "EMPLOYEE")
        );
    }

    protected String supportToken(String subject, List<String> allowedApplicationCodes) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of("tickets:read:queue"),
            Map.of("actor_type", "IT_SUPPORT", "support_queues", allowedApplicationCodes)
        );
    }

    protected ResponseEntity<String> getTicket(UUID ticketId, String bearerToken) {
        return getTicket(ticketId, bearerToken, null);
    }

    protected ResponseEntity<String> getTicket(UUID ticketId, String bearerToken, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange("/api/v1/tickets/" + ticketId, HttpMethod.GET, entity, String.class);
    }

    protected JsonNode bodyAsJson(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse response body as JSON", e);
        }
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
