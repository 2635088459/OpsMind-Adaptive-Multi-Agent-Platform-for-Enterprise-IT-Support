package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Query-side PostgreSQL projection adapter for Get Ticket (SPEC-TW-002
 * §11). Uses explicit SQL joined on {@code current_resolution_cycle_id}
 * (unique per resolution cycle) so each view is exactly one indexed query —
 * no full Ticket aggregate, no lazy JPA graph, no N+1. Resource-level
 * authorization (requester ownership / application-code scope) is part of
 * the {@code WHERE} clause, not a post-query filter.
 */
@Component
public class JdbcTicketQueryAdapter implements TicketQueryPort {

    private static final String EMPLOYEE_VIEW_SQL = """
        SELECT t.ticket_id, t.display_id, t.title, t.initial_description, t.application_code, t.source,
               t.status, t.priority, t.created_at, t.updated_at, t.version,
               s.status AS sla_status, s.response_due_at, s.resolution_due_at
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_sla_cycles s ON s.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = :ticketId AND t.requester_id = :requesterId
        """;

    private static final String SUPPORT_VIEW_SQL = """
        SELECT t.ticket_id, t.display_id, t.title, t.initial_description, t.application_code, t.source,
               t.status, t.priority, t.requester_id, t.current_team_id, t.current_support_user_id,
               t.created_at, t.updated_at, t.version,
               rc.cycle_number, rc.cycle_status,
               s.status AS sla_status, s.policy_id, s.response_due_at, s.resolution_due_at
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles rc ON rc.resolution_cycle_id = t.current_resolution_cycle_id
        LEFT JOIN ticket.ticket_sla_cycles s ON s.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = :ticketId AND t.application_code IN (:applicationCodes)
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcTicketQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<EmployeeTicketProjection> findEmployeeView(TicketId ticketId, String requesterId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("requesterId", requesterId);
        List<EmployeeTicketProjection> rows = jdbcTemplate.query(EMPLOYEE_VIEW_SQL, params, this::mapEmployeeRow);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<SupportTicketProjection> findSupportView(TicketId ticketId, Set<ApplicationCode> allowedApplicationCodes) {
        List<String> codes = allowedApplicationCodes.stream().map(Enum::name).toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("applicationCodes", codes);
        List<SupportTicketProjection> rows = jdbcTemplate.query(SUPPORT_VIEW_SQL, params, this::mapSupportRow);
        return rows.stream().findFirst();
    }

    private EmployeeTicketProjection mapEmployeeRow(ResultSet rs, int rowNum) throws SQLException {
        return new EmployeeTicketProjection(
            rs.getObject("ticket_id", UUID.class),
            rs.getString("display_id"),
            rs.getString("title"),
            rs.getString("initial_description"),
            rs.getString("application_code"),
            rs.getString("source"),
            rs.getString("status"),
            rs.getString("priority"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getLong("version"),
            rs.getString("sla_status"),
            toInstant(rs.getTimestamp("response_due_at")),
            toInstant(rs.getTimestamp("resolution_due_at"))
        );
    }

    private SupportTicketProjection mapSupportRow(ResultSet rs, int rowNum) throws SQLException {
        Object cycleNumberValue = rs.getObject("cycle_number");
        Integer cycleNumber = cycleNumberValue == null ? null : ((Number) cycleNumberValue).intValue();
        return new SupportTicketProjection(
            rs.getObject("ticket_id", UUID.class),
            rs.getString("display_id"),
            rs.getString("title"),
            rs.getString("initial_description"),
            rs.getString("application_code"),
            rs.getString("source"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getString("requester_id"),
            rs.getString("current_team_id"),
            rs.getString("current_support_user_id"),
            cycleNumber,
            rs.getString("cycle_status"),
            rs.getString("sla_status"),
            rs.getString("policy_id"),
            toInstant(rs.getTimestamp("response_due_at")),
            toInstant(rs.getTimestamp("resolution_due_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getLong("version")
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
