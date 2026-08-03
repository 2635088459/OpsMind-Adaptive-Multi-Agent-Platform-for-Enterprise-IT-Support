package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SPEC-TW-010 persistence §7: the ticket row joined with its current resolution-cycle row. */
@Component
public class TicketResolveGuardAdapter implements TicketResolveGuardPort {

    private static final String SELECT_SQL = """
        SELECT t.display_id, t.status, t.version, t.support_queue_id, t.current_team_id, t.current_support_user_id,
               t.current_resolution_cycle_id, c.cycle_status
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketResolveGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketResolveGuard> loadGuard(TicketId ticketId) {
        List<TicketResolveGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                return new TicketResolveGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_team_id"),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus)
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
