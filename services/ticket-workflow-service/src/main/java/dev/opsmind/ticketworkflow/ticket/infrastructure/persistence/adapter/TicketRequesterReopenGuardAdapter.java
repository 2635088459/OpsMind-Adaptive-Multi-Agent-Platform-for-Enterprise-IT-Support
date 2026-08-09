package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuardPort;
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

/**
 * SPEC-TW-028 persistence: the ticket row joined with its current
 * resolution cycle plus {@code requester_id} (mirrors {@code
 * TicketCloseReopenGuardAdapter}, SPEC-TW-011, with that one extra column).
 * The write path reuses {@link TicketReopenPersistenceAdapter} (SPEC-TW-011)
 * unchanged — the ticket-row/cycle-archival/new-cycle-insert SQL has no
 * queue- or actor-specific logic at all, so no new migration or write
 * adapter is needed.
 */
@Component
public class TicketRequesterReopenGuardAdapter implements TicketRequesterReopenGuardPort {

    private static final String SELECT_SQL = """
        SELECT t.display_id, t.requester_id, t.status, t.version, t.support_queue_id, t.current_support_user_id,
               t.current_resolution_cycle_id, t.reopen_count, c.cycle_status, c.cycle_number
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketRequesterReopenGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketRequesterReopenGuard> loadGuard(TicketId ticketId) {
        List<TicketRequesterReopenGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                return new TicketRequesterReopenGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    rs.getString("requester_id"),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus),
                    rs.getInt("cycle_number"),
                    rs.getInt("reopen_count")
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
