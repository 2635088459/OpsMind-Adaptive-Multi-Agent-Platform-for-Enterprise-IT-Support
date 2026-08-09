package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdateOutcome;
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
 * SPEC-TW-029 persistence: the guard read is a plain ticket-row lookup
 * (mirrors {@code TicketResolutionConfirmationGuardAdapter}'s shape) — no
 * resolution-cycle join is needed since Cancel does not gate on cycle
 * status. The write path updates the ticket row (dynamic {@code
 * expectedStatus}, mirroring {@code TicketReopenPersistenceAdapter}'s
 * multi-source-status shape) and marks the current resolution cycle {@code
 * CANCELLED} — both against the same transaction.
 */
@Component
public class TicketCancelPersistenceAdapter implements TicketCancelGuardPort, TicketCancelRepository {

    private static final String GUARD_SQL = """
        SELECT display_id, requester_id, status, version, support_queue_id, current_support_user_id, current_resolution_cycle_id
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'CANCELLED',
            cancelled_at = :cancelledAt,
            cancel_reason_code = :cancelReasonCode,
            active_workflow_id = NULL,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = :expectedStatus
        """;

    private static final String UPDATE_CYCLE_SQL = """
        UPDATE ticket.ticket_resolution_cycles
        SET cycle_status = 'CANCELLED'
        WHERE resolution_cycle_id = :resolutionCycleId
          AND ticket_id = :ticketId
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketCancelPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketCancelGuard> loadGuard(TicketId ticketId) {
        List<TicketCancelGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new TicketCancelGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    rs.getString("requester_id"),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    (UUID) rs.getObject("current_resolution_cycle_id")
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public TicketCancelUpdateOutcome applyCancel(TicketCancelUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("cancelledAt", Timestamp.from(update.cancelledAt()))
            .addValue("cancelReasonCode", update.cancelReasonCode().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("expectedStatus", update.expectedStatus().name())
            .addValue("resolutionCycleId", update.resolutionCycleId());

        int ticketRowsAffected = namedParameterJdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int cycleRowsAffected = namedParameterJdbcTemplate.update(UPDATE_CYCLE_SQL, params);
        if (cycleRowsAffected != 1) {
            return new TicketCancelUpdateOutcome.ResolutionCycleConflict();
        }

        return new TicketCancelUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketCancelUpdateOutcome reclassify(TicketCancelUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketCancelUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketCancelUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketCancelUpdateOutcome.InvalidState(currentStatus);
    }
}
