package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TicketStatusTransitionGuardAdapter implements TicketStatusTransitionGuardPort {

    private static final String SELECT_SQL = """
        SELECT display_id, status, version, support_queue_id, current_team_id, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketStatusTransitionGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketStatusTransitionGuard> loadGuard(TicketId ticketId) {
        List<TicketStatusTransitionGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new TicketStatusTransitionGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_team_id"),
                    rs.getString("current_support_user_id")
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
