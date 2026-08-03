package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.UserInputRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SPEC-TW-013: the ticket row joined with the specific (URL-named) user-input-request row, if it exists and belongs to this ticket. */
@Component
public class TicketUserReplyGuardAdapter implements TicketUserReplyGuardPort {

    private static final String SELECT_SQL = """
        SELECT t.display_id, t.requester_id, t.status, t.version, r.ticket_id AS request_ticket_id, r.request_status
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_user_input_requests r ON r.request_id = ?
        WHERE t.ticket_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketUserReplyGuardAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketUserReplyGuard> loadGuard(TicketId ticketId, UUID requestId) {
        List<TicketUserReplyGuard> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> {
                UUID requestTicketId = (UUID) rs.getObject("request_ticket_id");
                String requestStatus = rs.getString("request_status");
                boolean requestExistsForTicket = requestTicketId != null && requestTicketId.equals(ticketId.value());
                return new TicketUserReplyGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    rs.getString("requester_id"),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    requestExistsForTicket,
                    requestExistsForTicket ? UserInputRequestStatus.valueOf(requestStatus) : null
                );
            },
            requestId, ticketId.value()
        );
        return rows.stream().findFirst();
    }
}
