package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageWriteGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketWriteGuard;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Loads only the fields Add Ticket Message needs to authorize and
 * state-guard itself (SPEC-TW-004 §12 step 2) — not the full Ticket
 * aggregate, no lazy JPA graph.
 */
@Component
public class TicketMessageWriteGuardAdapter implements TicketMessageWriteGuardPort {

    private static final String SELECT_SQL = """
        SELECT requester_id, application_code, status
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketMessageWriteGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketWriteGuard> loadGuard(TicketId ticketId) {
        List<TicketWriteGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new TicketWriteGuard(
                ticketId,
                rs.getString("requester_id"),
                ApplicationCode.valueOf(rs.getString("application_code")),
                TicketStatus.valueOf(rs.getString("status"))
            ),
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
