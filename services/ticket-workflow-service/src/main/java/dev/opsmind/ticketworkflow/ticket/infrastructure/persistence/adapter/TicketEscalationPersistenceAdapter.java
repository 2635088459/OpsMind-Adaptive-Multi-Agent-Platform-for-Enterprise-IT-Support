package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdateOutcome;
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
 * SPEC-TW-031 persistence: the guard read is a plain ticket-row lookup
 * (mirrors {@code TicketCancelPersistenceAdapter}'s shape, plus {@code
 * team_id}/{@code active_workflow_id}). The write path updates the ticket
 * row (dynamic {@code expectedStatus}, mirroring {@code
 * TicketCancelPersistenceAdapter}'s multi-source-status shape) and clears
 * {@code active_workflow_id} — domain-rules: "Escalation freezes automated
 * progression until an explicit resume or cancel command."
 */
@Component
public class TicketEscalationPersistenceAdapter implements TicketEscalationGuardPort, TicketEscalationRepository {

    private static final String GUARD_SQL = """
        SELECT display_id, status, version, current_team_id, support_queue_id,
               current_support_user_id, current_resolution_cycle_id, active_workflow_id
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'ESCALATED',
            escalated_at = :escalatedAt,
            escalated_by = :escalatedBy,
            escalation_reason_code = :escalationReasonCode,
            active_workflow_id = NULL,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = :expectedStatus
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketEscalationPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketEscalationGuard> loadGuard(TicketId ticketId) {
        List<TicketEscalationGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new TicketEscalationGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getString("current_team_id"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    (UUID) rs.getObject("current_resolution_cycle_id"),
                    rs.getString("active_workflow_id")
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public TicketEscalationUpdateOutcome applyEscalation(TicketEscalationUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("escalatedAt", Timestamp.from(update.escalatedAt()))
            .addValue("escalatedBy", update.escalatedById())
            .addValue("escalationReasonCode", update.escalationReasonCode().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("expectedStatus", update.expectedStatus().name());

        int rowsAffected = namedParameterJdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (rowsAffected != 1) {
            return reclassify(update);
        }

        return new TicketEscalationUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketEscalationUpdateOutcome reclassify(TicketEscalationUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketEscalationUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketEscalationUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketEscalationUpdateOutcome.InvalidState(currentStatus);
    }
}
