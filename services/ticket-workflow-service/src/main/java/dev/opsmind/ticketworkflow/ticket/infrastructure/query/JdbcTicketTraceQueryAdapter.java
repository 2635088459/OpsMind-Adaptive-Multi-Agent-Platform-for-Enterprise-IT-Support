package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTraceQueryPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link TicketTraceQueryPort} over {@code ticket.audit_records}, which
 * every business action on a Ticket already writes to with the request's
 * real W3C {@code trace_id}. Indexed by {@code (resource_type, resource_id,
 * occurred_at)} — the same index the audit read paths use.
 */
@Component
public class JdbcTicketTraceQueryAdapter implements TicketTraceQueryPort {

    private static final String LATEST_TRACE_ID_SQL = """
        SELECT trace_id
        FROM ticket.audit_records
        WHERE resource_type = 'TICKET'
          AND resource_id = :ticketId
          AND trace_id IS NOT NULL
          AND trace_id <> ''
        ORDER BY occurred_at DESC
        LIMIT 1
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcTicketTraceQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> latestTraceIdForTicket(TicketId ticketId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ticketId", ticketId.value().toString());
        List<String> rows = jdbcTemplate.queryForList(LATEST_TRACE_ID_SQL, params, String.class);
        return rows.stream().findFirst();
    }
}
