package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
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
 * SPEC-TW-026 persistence: the guard read joins the ticket with its current
 * resolution cycle plus {@code requester_id} (mirrors {@code
 * TicketCloseReopenGuardAdapter}, SPEC-TW-011, with that one extra column).
 * The write path reuses the exact {@code tickets}/{@code
 * ticket_resolution_cycles} columns Close already writes — {@code
 * close_reason_code} accepts {@code REQUESTER_CONFIRMED}/{@code
 * SUPPORT_CONFIRMED} (both already part of {@link CloseReasonCode}'s CHECK
 * constraint) — so no new migration is needed; a confirmed resolution is,
 * physically, still a close.
 */
@Component
public class TicketResolutionConfirmationPersistenceAdapter implements TicketResolutionConfirmationGuardPort, TicketResolutionConfirmationRepository {

    private static final String GUARD_SQL = """
        SELECT t.display_id, t.requester_id, t.status, t.version, t.support_queue_id, t.current_support_user_id,
               t.current_resolution_cycle_id, c.cycle_status
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'CLOSED',
            closed_at = :confirmedAt,
            closed_by = :confirmedById,
            close_reason_code = :reasonCode,
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
            closed_at = :confirmedAt,
            closed_by_type = :confirmedByType,
            closed_by_id = :confirmedById,
            close_reason_code = :reasonCode
        WHERE resolution_cycle_id = :resolutionCycleId
          AND ticket_id = :ticketId
          AND cycle_status = 'RESOLVED'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketResolutionConfirmationPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketResolutionConfirmationGuard> loadGuard(TicketId ticketId) {
        List<TicketResolutionConfirmationGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                return new TicketResolutionConfirmationGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    rs.getString("requester_id"),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus)
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public TicketResolutionConfirmationUpdateOutcome applyConfirmation(TicketResolutionConfirmationUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("confirmedAt", Timestamp.from(update.confirmedAt()))
            .addValue("confirmedById", update.confirmedById())
            .addValue("confirmedByType", update.confirmedByType())
            .addValue("reasonCode", CloseReasonCode.valueOf(update.reasonCode().name()).name())
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
            return new TicketResolutionConfirmationUpdateOutcome.ResolutionCycleConflict();
        }

        return new TicketResolutionConfirmationUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketResolutionConfirmationUpdateOutcome reclassify(TicketResolutionConfirmationUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketResolutionConfirmationUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketResolutionConfirmationUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketResolutionConfirmationUpdateOutcome.InvalidState(currentStatus);
    }
}
