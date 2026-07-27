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
 * Shared fixture for List Requester Tickets PostgreSQL integration tests:
 * full Spring context, real PostgreSQL Testcontainer, real signed JWTs, and
 * direct SQL seeding so each test controls exact {@code createdAt} values
 * (needed for keyset-pagination and tie-breaker scenarios) without going
 * through the Create Ticket command flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractListRequesterTicketsIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * List queries return every row for a requester, so leftover rows from
     * an earlier {@code @Test} method in the same class (sharing one
     * container/database) would silently contaminate a later test's
     * result set. Truncating {@code tickets} (cascading to resolution
     * cycles, SLA cycles, status history, and outbox events via FK)
     * before each test keeps every test's seed data isolated.
     */
    @BeforeEach
    void resetTicketData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets CASCADE");
    }

    protected UUID seedTicket(String requesterId, String applicationCode, String status, Instant createdAt) {
        return seedTicket(UUID.randomUUID(), requesterId, applicationCode, status, createdAt);
    }

    protected UUID seedTicket(UUID ticketId, String requesterId, String applicationCode, String status, Instant createdAt) {
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, 'UNASSIGNED', ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, requesterId,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            applicationCode, status, resolutionCycleId, Timestamp.from(createdAt), Timestamp.from(createdAt), requesterId
        );

        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(createdAt)
        );

        return ticketId;
    }

    protected String employeeToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "employee-portal", Set.of("tickets:read:self"), Map.of("actor_type", "EMPLOYEE")
        );
    }

    protected ResponseEntity<String> listTickets(String bearerToken, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/v1/tickets");
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
