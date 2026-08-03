package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-011 persistence §3/§5: the ticket-row update and resolution-cycle
 * closure, executed as two statements against the same Spring-managed
 * transaction (mirrors {@code TicketResolvePersistenceAdapter}, SPEC-TW-010).
 */
@Component
public class TicketClosePersistenceAdapter implements TicketCloseRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'CLOSED',
            closed_at = :closedAt,
            closed_by = :closedById,
            close_reason_code = :closeReasonCode,
            auto_close_due_at = NULL,
            active_workflow_id = NULL,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'RESOLVED'
        """;

    private static final String UPDATE_CYCLE_SQL = """
        UPDATE ticket.ticket_resolution_cycles
        SET cycle_status = 'CLOSED',
            closed_at = :closedAt,
            closed_by_type = :closedByType,
            closed_by_id = :closedById,
            close_reason_code = :closeReasonCode
        WHERE resolution_cycle_id = :resolutionCycleId
          AND ticket_id = :ticketId
          AND cycle_status = 'RESOLVED'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketClosePersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketCloseUpdateOutcome applyClose(TicketCloseUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("closedAt", Timestamp.from(update.closedAt()))
            .addValue("closedById", update.closedById())
            .addValue("closedByType", update.closedByType())
            .addValue("closeReasonCode", update.closeReasonCode().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("resolutionCycleId", update.resolutionCycleId());

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int cycleRowsAffected = jdbcTemplate.update(UPDATE_CYCLE_SQL, params);
        if (cycleRowsAffected != 1) {
            return new TicketCloseUpdateOutcome.ResolutionCycleConflict();
        }

        return new TicketCloseUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketCloseUpdateOutcome reclassify(TicketCloseUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketCloseUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketCloseUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketCloseUpdateOutcome.InvalidState(currentStatus);
    }
}
