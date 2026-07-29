package dev.opsmind.ticketworkflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTimelineQueryPort;
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
 * Shared fixture for Ticket Timeline PostgreSQL integration tests
 * (SPEC-TW-006): full Spring context, real PostgreSQL Testcontainer, real
 * signed JWTs, and direct SQL seeding of a Ticket plus its Timeline sources
 * (status history, messages) without going through the Create Ticket or Add
 * Ticket Message command flows — mirrors {@link AbstractSupportQueueIT} and
 * {@code AbstractGetTicketIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractTicketTimelineIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    protected static final Instant DEFAULT_CREATED_AT = Instant.parse("2026-07-23T16:30:00Z");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TicketTimelineQueryPort timelineQueryPort;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Every Timeline query returns every authorized row for a Ticket, so
     * leftover rows from an earlier {@code @Test} method sharing one
     * container/database would silently contaminate a later test's result
     * set. Truncating {@code tickets} (cascading to status history,
     * messages, and outbox events via FK) before each test keeps every
     * test's seed data isolated.
     */
    @BeforeEach
    void resetTicketData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets CASCADE");
    }

    protected UUID seedTicket() {
        return seedTicket(UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
    }

    protected UUID seedTicket(String requesterId, String applicationCode) {
        return seedTicket(UUID.randomUUID(), requesterId, applicationCode, DEFAULT_CREATED_AT);
    }

    protected UUID seedTicket(String requesterId, String applicationCode, Instant createdAt) {
        return seedTicket(UUID.randomUUID(), requesterId, applicationCode, createdAt);
    }

    /**
     * {@code tickets.current_resolution_cycle_id} is {@code NOT NULL} but
     * carries no foreign key back to {@code ticket_resolution_cycles}
     * (V002/V003), so — unlike Support Queue's SLA-rank seeding — a Timeline
     * seed does not need a matching resolution-cycle or SLA-cycle row at
     * all: the Timeline projection never joins either table.
     */
    protected UUID seedTicket(UUID ticketId, String requesterId, String applicationCode, Instant createdAt) {
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
            applicationCode, UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt), requesterId
        );

        return ticketId;
    }

    protected UUID seedStatusHistory(UUID ticketId, String fromStatus, String toStatus, Instant occurredAt, long aggregateVersion) {
        return seedStatusHistory(ticketId, fromStatus, toStatus, "IT_SUPPORT", "support-100", occurredAt, aggregateVersion);
    }

    protected UUID seedStatusHistory(
        UUID ticketId, String fromStatus, String toStatus, String actorType, String actorId, Instant occurredAt, long aggregateVersion
    ) {
        UUID historyId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_status_history
                (history_id, ticket_id, from_status, to_status, transition_id, reason_code, actor_type, actor_id, aggregate_version, occurred_at)
            VALUES (?, ?, ?, ?, 'SM-003', 'INVESTIGATION_STARTED', ?, ?, ?, ?)
            """,
            historyId, ticketId, fromStatus, toStatus, actorType, actorId, aggregateVersion, Timestamp.from(occurredAt)
        );
        return historyId;
    }

    protected UUID seedMessage(UUID ticketId, String messageType, String visibility, String actorType, String actorId, String content, Instant occurredAt) {
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_messages
                (message_id, ticket_id, message_type, visibility, author_type, author_id, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            messageId, ticketId, messageType, visibility, actorType, actorId, content, Timestamp.from(occurredAt)
        );
        return messageId;
    }

    protected UUID seedPublicRequesterMessage(UUID ticketId, String content, Instant occurredAt) {
        return seedMessage(ticketId, "PUBLIC_REQUESTER_MESSAGE", "PUBLIC", "EMPLOYEE", DEFAULT_REQUESTER, content, occurredAt);
    }

    protected UUID seedPublicSupportMessage(UUID ticketId, String content, Instant occurredAt) {
        return seedMessage(ticketId, "PUBLIC_SUPPORT_MESSAGE", "PUBLIC", "IT_SUPPORT", "support-100", content, occurredAt);
    }

    protected UUID seedInternalSupportNote(UUID ticketId, String content, Instant occurredAt) {
        return seedMessage(ticketId, "INTERNAL_SUPPORT_NOTE", "INTERNAL", "IT_SUPPORT", "support-100", content, occurredAt);
    }

    protected String employeeToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "employee-portal", Set.of("tickets:read:self"), Map.of("actor_type", "EMPLOYEE")
        );
    }

    protected String supportToken(String subject, Set<String> allowedApplicationCodes) {
        return supportToken(subject, allowedApplicationCodes, false);
    }

    protected String supportToken(String subject, Set<String> allowedApplicationCodes, boolean internalScope) {
        Set<String> scopes = internalScope
            ? Set.of("tickets:read:queue", "tickets:timeline:internal")
            : Set.of("tickets:read:queue");
        return TestJwtSupport.mintToken(
            subject, "support-console", scopes,
            Map.of("actor_type", "IT_SUPPORT", "support_queues", List.copyOf(allowedApplicationCodes))
        );
    }

    protected String auditorToken(String subject) {
        return TestJwtSupport.mintToken(
            subject, "audit-console", Set.of("tickets:audit:timeline"), Map.of("actor_type", "AUDITOR")
        );
    }

    protected ResponseEntity<String> getTimeline(UUID ticketId, String bearerToken) {
        return getTimeline(ticketId, bearerToken, Map.of());
    }

    protected ResponseEntity<String> getTimeline(UUID ticketId, String bearerToken, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/v1/tickets/" + ticketId + "/timeline");
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

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
