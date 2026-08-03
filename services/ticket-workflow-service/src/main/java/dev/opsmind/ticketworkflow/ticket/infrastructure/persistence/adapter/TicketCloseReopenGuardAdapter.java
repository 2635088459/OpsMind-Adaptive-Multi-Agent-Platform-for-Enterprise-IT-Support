package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SPEC-TW-011: the ticket row joined with its current resolution-cycle row, shared by Close and Reopen. */
@Component
public class TicketCloseReopenGuardAdapter implements TicketCloseReopenGuardPort {

    private static final String SELECT_SQL = """
        SELECT t.display_id, t.status, t.version, t.support_queue_id, t.current_team_id, t.current_support_user_id,
               t.current_resolution_cycle_id, t.reopen_count, t.closed_at, c.cycle_status, c.cycle_number
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketCloseReopenGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketCloseReopenGuard> loadGuard(TicketId ticketId) {
        List<TicketCloseReopenGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                Timestamp closedAt = rs.getTimestamp("closed_at");
                return new TicketCloseReopenGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_team_id"),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus),
                    rs.getInt("cycle_number"),
                    rs.getInt("reopen_count"),
                    closedAt == null ? null : closedAt.toInstant()
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
