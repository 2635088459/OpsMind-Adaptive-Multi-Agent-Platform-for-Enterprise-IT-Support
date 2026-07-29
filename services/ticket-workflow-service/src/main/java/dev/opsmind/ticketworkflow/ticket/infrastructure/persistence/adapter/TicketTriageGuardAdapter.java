package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Loads only the fields Triage needs to state/version-guard itself
 * (SPEC-TW-007 §12 step 2) — not the full Ticket aggregate.
 */
@Component
public class TicketTriageGuardAdapter implements TicketTriageGuardPort {

    private static final String SELECT_SQL = """
        SELECT display_id, status, version
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketTriageGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketTriageGuard> loadGuard(TicketId ticketId) {
        List<TicketTriageGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new TicketTriageGuard(
                ticketId,
                TicketDisplayId.of(rs.getString("display_id")),
                TicketStatus.valueOf(rs.getString("status")),
                rs.getLong("version")
            ),
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
