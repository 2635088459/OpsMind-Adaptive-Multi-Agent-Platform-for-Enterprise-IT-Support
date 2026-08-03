package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Component
public class TicketStatusTransitionPersistenceAdapter implements TicketStatusTransitionRepository {

    private static final String UPDATE_SQL = """
        UPDATE ticket.tickets
        SET status = :newStatus,
            waiting_for_requester_since = :waitingForRequesterSince,
            approval_reference = :approvalReference,
            version = version + 1,
            updated_at = :updatedAt
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = :expectedStatus
          AND current_support_user_id IS NOT NULL
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketStatusTransitionPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketStatusTransitionUpdateOutcome applyTransition(TicketStatusTransitionUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("newStatus", update.newStatus().name())
            .addValue("waitingForRequesterSince", update.waitingForRequesterSince() == null ? null : Timestamp.from(update.waitingForRequesterSince()))
            .addValue("approvalReference", update.approvalReference())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("expectedStatus", update.expectedStatus().name());

        int affected = jdbcTemplate.update(UPDATE_SQL, params);
        if (affected == 1) {
            return new TicketStatusTransitionUpdateOutcome.Updated(update.expectedVersion() + 1);
        }
        return reclassify(update);
    }

    private TicketStatusTransitionUpdateOutcome reclassify(TicketStatusTransitionUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketStatusTransitionUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new TicketStatusTransitionUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new TicketStatusTransitionUpdateOutcome.NotAssigned();
        }
        return new TicketStatusTransitionUpdateOutcome.InvalidState(currentStatus);
    }
}
