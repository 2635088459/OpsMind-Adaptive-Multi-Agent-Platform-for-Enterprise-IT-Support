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
 * Shared fixture for Assign/Reassign/Unassign Ticket (SPEC-TW-008)
 * PostgreSQL integration tests: full Spring context, real PostgreSQL
 * Testcontainer, real signed JWTs, and direct SQL seeding of the parent
 * Ticket plus V014's new catalog tables (agent directory, queue
 * membership). Mirrors {@code AbstractTriageTicketIT}'s structure and
 * helper-naming conventions closely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractTicketAssignmentIT implements InfrastructureContainerSupport {

    protected static final String DEFAULT_REQUESTER = "employee-123";
    protected static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    protected static final String DEFAULT_TEAM_ID = "TEAM-HOUSING";
    protected static final String ASSIGN_SCOPE = "ticket:assign";
    protected static final String DEFAULT_REASON = "Primary endpoint support owner";
    protected static final String DEFAULT_ASSIGNEE_ID = "agent-1";
    protected static final String OTHER_ASSIGNEE_ID = "agent-2";
    protected static final Instant SEED_NOW = Instant.parse("2026-07-23T16:30:00Z");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * {@code ticket.tickets} CASCADE also removes its dependent status/
     * assignment history, outbox, message, and resolution-cycle rows.
     * {@code support_agents}/{@code support_queue_memberships} are listed
     * explicitly since Postgres computes the full CASCADE closure across
     * every table named regardless of order.
     */
    @BeforeEach
    void resetAssignmentData() {
        jdbcTemplate.execute("TRUNCATE TABLE ticket.tickets, ticket.ticket_categories, ticket.support_queues, ticket.support_agents CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.idempotency_records");
        jdbcTemplate.execute("TRUNCATE TABLE ticket.audit_records");
    }

    protected UUID seedCategory(boolean active) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_categories (category_id, code, display_name, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            categoryId, "CODE-" + categoryId, "Category " + categoryId, active, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
        return categoryId;
    }

    protected UUID seedSupportQueue(String teamId, boolean active) {
        return seedSupportQueue(UUID.randomUUID(), teamId, active);
    }

    protected UUID seedSupportQueue(UUID supportQueueId, String teamId, boolean active) {
        jdbcTemplate.update("""
            INSERT INTO ticket.support_queues (support_queue_id, team_id, display_name, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            supportQueueId, teamId, "Queue for " + teamId, active, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
        return supportQueueId;
    }

    protected void seedSupportAgent(String agentId, String role, boolean active) {
        jdbcTemplate.update("""
            INSERT INTO ticket.support_agents (agent_id, display_name, role, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            agentId, "Display " + agentId, role, active, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
    }

    protected void seedQueueMembership(String agentId, UUID supportQueueId) {
        jdbcTemplate.update("""
            INSERT INTO ticket.support_queue_memberships (agent_id, support_queue_id, created_at)
            VALUES (?, ?, ?)
            """,
            agentId, supportQueueId, Timestamp.from(SEED_NOW)
        );
    }

    /**
     * Seeds a {@code TRIAGED} ticket with no assignee, ready for {@code
     * /assign}: {@code category_id}/{@code support_queue_id}/{@code
     * triaged_by}/{@code triaged_at} set (ck_tickets_triaged_fields),
     * {@code current_support_user_id} left {@code NULL}
     * (ck_tickets_triaged_no_assignee), and {@code current_team_id} set to
     * the queue's team — the field the real Triage persistence adapter sets
     * at Triage time (SPEC-TW-007 deviation #4) and that {@code
     * TicketAssignmentGuardAdapter} reads directly for queue-scope
     * authorization.
     */
    protected UUID seedTriagedTicketReadyToAssign(String teamId, UUID supportQueueId) {
        return seedTriagedTicketReadyToAssign(UUID.randomUUID(), teamId, supportQueueId);
    }

    protected UUID seedTriagedTicketReadyToAssign(UUID ticketId, String teamId, UUID supportQueueId) {
        UUID categoryId = seedCategory(true);
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at, current_team_id,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, 'HIGH', 'TRIAGED', ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, categoryId, supportQueueId, "support-000", Timestamp.from(SEED_NOW), teamId,
            resolutionCycleId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        seedResolutionCycle(resolutionCycleId, ticketId);
        return ticketId;
    }

    /**
     * Seeds an {@code ASSIGNED} ticket owned by {@code assigneeId}, ready
     * for {@code /reassign} or {@code /unassign}
     * (ck_tickets_assigned_fields: {@code current_support_user_id}/{@code
     * assigned_at}/{@code assigned_by} all set).
     */
    protected UUID seedAssignedTicket(String teamId, UUID supportQueueId, String assigneeId) {
        return seedAssignedTicket(UUID.randomUUID(), teamId, supportQueueId, assigneeId, TicketAssignmentStatus.ASSIGNED);
    }

    protected UUID seedAssignedTicket(UUID ticketId, String teamId, UUID supportQueueId, String assigneeId) {
        return seedAssignedTicket(ticketId, teamId, supportQueueId, assigneeId, TicketAssignmentStatus.ASSIGNED);
    }

    /** Overload used by reassign tests that need a {@code WAITING_FOR_USER}/{@code WAITING_FOR_APPROVAL} owned ticket. */
    protected UUID seedAssignedTicket(UUID ticketId, String teamId, UUID supportQueueId, String assigneeId, TicketAssignmentStatus status) {
        UUID categoryId = seedCategory(true);
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at, current_team_id, current_support_user_id,
                 assigned_at, assigned_by,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, categoryId, supportQueueId, "support-000", Timestamp.from(SEED_NOW), teamId, assigneeId,
            Timestamp.from(SEED_NOW), "support-000",
            status.name(), resolutionCycleId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        seedResolutionCycle(resolutionCycleId, ticketId);
        return ticketId;
    }

    /** Seeds a ticket in an arbitrary status (neither {@code TRIAGED} nor {@code ASSIGNED}) for state-guard negative tests. */
    protected UUID seedTicketInStatus(UUID ticketId, String status) {
        return seedTicketInStatus(ticketId, status, null);
    }

    /**
     * Like {@link #seedTicketInStatus(UUID, String)}, but also sets {@code
     * current_team_id} so the request can pass {@code
     * TicketAssignmentApplicationService#checkQueueAccess} (which runs
     * BEFORE the domain-level state check) and actually reach the state
     * guard under test, rather than failing earlier with {@code
     * QUEUE_ACCESS_DENIED}.
     */
    protected UUID seedTicketInStatus(UUID ticketId, String status, String teamId) {
        return seedTicketInStatus(ticketId, status, teamId, null);
    }

    /**
     * Like {@link #seedTicketInStatus(UUID, String, String)}, additionally
     * setting {@code support_queue_id} — needed whenever the scenario must
     * reach {@code resolveEligibleAssignee} (which dereferences the guard's
     * {@code supportQueueId} and NPEs on a null one) before the state check
     * under test.
     */
    protected UUID seedTicketInStatus(UUID ticketId, String status, String teamId, UUID supportQueueId) {
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_team_id, support_queue_id, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, 'UNASSIGNED', ?, ?, ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, status, teamId, supportQueueId, resolutionCycleId,
            Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        seedResolutionCycle(resolutionCycleId, ticketId);
        return ticketId;
    }

    /**
     * Seeds a {@code RESOLVED} ticket that still carries an assignee — a
     * status outside {@code Ticket.REASSIGNABLE_STATUSES} but, unlike
     * {@code TRIAGED} (which {@code ck_tickets_triaged_no_assignee}
     * forbids from ever carrying an owner), one the schema does not forbid
     * from carrying one. This is the only way to reach Reassign's {@code
     * InvalidTicketStateException} through a real HTTP call without
     * violating a DB CHECK constraint (see the state-guard IT's report
     * note: a literally-TRIAGED ticket can never reach Reassign's state
     * check, because it always fails the earlier "has a current assignee"
     * check instead).
     */
    protected UUID seedResolvedTicketWithAssignee(String teamId, UUID supportQueueId, String assigneeId) {
        UUID ticketId = UUID.randomUUID();
        UUID categoryId = seedCategory(true);
        UUID resolutionCycleId = UUID.randomUUID();
        String displayId = "INC-" + Math.abs(ticketId.hashCode());

        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 category_id, support_queue_id, triaged_by, triaged_at, current_team_id, current_support_user_id,
                 assigned_at, assigned_by, priority, status, resolved_at, auto_close_due_at,
                 resolved_by, resolution_code, resolution_summary,
                 current_resolution_cycle_id, created_at, updated_at, version, created_by_type, created_by_id)
            VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', 'RESOLVED', ?, ?, ?, ?, ?, ?, ?, ?, 0, 'EMPLOYEE', ?)
            """,
            ticketId, displayId, DEFAULT_REQUESTER,
            "Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.",
            DEFAULT_APPLICATION_CODE, categoryId, supportQueueId, "support-000", Timestamp.from(SEED_NOW), teamId, assigneeId,
            Timestamp.from(SEED_NOW), "support-000",
            Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW.plusSeconds(3600)),
            assigneeId, "FIXED", "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
            resolutionCycleId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW), DEFAULT_REQUESTER
        );

        seedResolutionCycle(resolutionCycleId, ticketId);
        return ticketId;
    }

    private void seedResolutionCycle(UUID resolutionCycleId, UUID ticketId) {
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?)
            """,
            resolutionCycleId, ticketId, Timestamp.from(SEED_NOW)
        );
    }

    protected String supportToken(String subject, Set<String> allowedTeamIds) {
        return TestJwtSupport.mintToken(
            subject, "support-console", Set.of(ASSIGN_SCOPE),
            Map.of("actor_type", "IT_SUPPORT", "support_teams", List.copyOf(allowedTeamIds))
        );
    }

    protected String supportTokenWithoutAssignScope(String subject, Set<String> allowedTeamIds) {
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

    protected String assignRequestBody(String assigneeId, String reason) {
        return "{\"assigneeId\":\"" + assigneeId + "\",\"reason\":\"" + reason + "\"}";
    }

    protected String unassignRequestBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    protected ResponseEntity<String> assign(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        return post(ticketId, "assign", bearerToken, ifMatch, idempotencyKey, body);
    }

    protected ResponseEntity<String> reassign(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        return post(ticketId, "reassign", bearerToken, ifMatch, idempotencyKey, body);
    }

    protected ResponseEntity<String> unassign(UUID ticketId, String bearerToken, String ifMatch, String idempotencyKey, String body) {
        return post(ticketId, "unassign", bearerToken, ifMatch, idempotencyKey, body);
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

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> ticketRow(UUID ticketId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ticket.tickets WHERE ticket_id = ?", ticketId);
    }

    protected Map<String, Object> assignmentHistoryRow(UUID ticketId) {
        return jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_assignment_history WHERE ticket_id = ? ORDER BY resulting_version DESC LIMIT 1", ticketId
        );
    }

    public enum TicketAssignmentStatus {
        ASSIGNED, IN_PROGRESS, WAITING_FOR_USER, WAITING_FOR_APPROVAL
    }
}
