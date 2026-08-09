package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-027 persistence: the guard read joins the ticket with its current
 * resolution cycle plus {@code auto_close_due_at} (mirrors {@code
 * TicketCloseReopenGuardAdapter}, SPEC-TW-011, with that one extra column).
 * The write path reuses the exact {@code tickets}/{@code
 * ticket_resolution_cycles} columns Close already writes — {@code
 * close_reason_code} is always {@code AUTO_CLOSE_TIMEOUT} (already part of
 * {@code CloseReasonCode}'s CHECK constraint) — so no new migration is
 * needed; an auto-closed ticket is, physically, still a close.
 */
@Component
public class TicketAutoClosePersistenceAdapter implements TicketAutoCloseGuardPort, TicketAutoCloseRepository {

    private static final String GUARD_SQL = """
        SELECT t.display_id, t.status, t.version, t.support_queue_id, t.current_support_user_id,
               t.current_resolution_cycle_id, t.auto_close_due_at, c.cycle_status
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

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

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketAutoClosePersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketAutoCloseGuard> loadGuard(TicketId ticketId) {
        List<TicketAutoCloseGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                Timestamp autoCloseDueAt = rs.getTimestamp("auto_close_due_at");
                return new TicketAutoCloseGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus),
                    autoCloseDueAt == null ? null : autoCloseDueAt.toInstant()
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public TicketAutoCloseUpdateOutcome applyAutoClose(TicketAutoCloseUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("closedAt", Timestamp.from(update.closedAt()))
            .addValue("closedById", update.closedById())
            .addValue("closedByType", update.closedByType())
            .addValue("closeReasonCode", update.closeReasonCode().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("resolutionCycleId", update.resolutionCycleId());

        int ticketRowsAffected = namedParameterJdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int cycleRowsAffected = namedParameterJdbcTemplate.update(UPDATE_CYCLE_SQL, params);
        if (cycleRowsAffected != 1) {
            return new TicketAutoCloseUpdateOutcome.ResolutionCycleConflict();
        }

        return new TicketAutoCloseUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketAutoCloseUpdateOutcome reclassify(TicketAutoCloseUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketAutoCloseUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketAutoCloseUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketAutoCloseUpdateOutcome.InvalidState(currentStatus);
    }
}
