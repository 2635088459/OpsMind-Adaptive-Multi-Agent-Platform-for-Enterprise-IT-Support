package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-010 persistence §7: the ticket-row update and resolution-cycle
 * completion, executed as two statements against the same Spring-managed
 * transaction (the caller's {@code @Transactional} service method) so both
 * commit or roll back together.
 */
@Component
public class TicketResolvePersistenceAdapter implements TicketResolveRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'RESOLVED',
            resolved_at = :resolvedAt,
            resolved_by = :resolvedById,
            resolution_code = :resolutionCode,
            resolution_summary = :resolutionSummary,
            waiting_for_requester_since = NULL,
            approval_reference = NULL,
            auto_close_due_at = :autoCloseDueAt,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'IN_PROGRESS'
          AND current_support_user_id IS NOT NULL
        """;

    private static final String UPDATE_CYCLE_SQL = """
        UPDATE ticket.ticket_resolution_cycles
        SET cycle_status = 'RESOLVED',
            resolved_at = :resolvedAt,
            resolution_code = :resolutionCode,
            resolution_summary = :resolutionSummary,
            resolved_by_type = :resolvedByType,
            resolved_by_id = :resolvedById
        WHERE resolution_cycle_id = :resolutionCycleId
          AND ticket_id = :ticketId
          AND cycle_status = 'ACTIVE'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketResolvePersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketResolveUpdateOutcome applyResolution(TicketResolveUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("resolvedAt", Timestamp.from(update.resolvedAt()))
            .addValue("resolvedById", update.resolvedById())
            .addValue("resolvedByType", update.resolvedByType())
            .addValue("resolutionCode", update.resolutionCode().name())
            .addValue("resolutionSummary", update.resolutionSummary())
            .addValue("autoCloseDueAt", Timestamp.from(update.autoCloseDueAt()))
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
            return new TicketResolveUpdateOutcome.ResolutionCycleConflict();
        }

        return new TicketResolveUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketResolveUpdateOutcome reclassify(TicketResolveUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketResolveUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new TicketResolveUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new TicketResolveUpdateOutcome.NotAssigned();
        }
        return new TicketResolveUpdateOutcome.InvalidState(currentStatus);
    }
}
