package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdateOutcome;
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
 * SPEC-TW-032 persistence: the guard read is a plain ticket-row lookup
 * (mirrors {@code TicketEscalationPersistenceAdapter}'s shape). The write
 * path only ever expects {@code status = 'ESCALATED'} — unlike Cancel/
 * Escalate's multi-source-status shape, Resume has exactly one legal
 * source status — and deliberately leaves {@code escalated_at}/{@code
 * escalated_by}/{@code escalation_reason_code}/{@code active_workflow_id}
 * untouched (domain-rules: "cannot discard the escalation resolution
 * notes").
 */
@Component
public class TicketEscalationResumePersistenceAdapter implements TicketEscalationResumeGuardPort, TicketEscalationResumeRepository {

    private static final String GUARD_SQL = """
        SELECT display_id, status, version, current_team_id, support_queue_id,
               current_support_user_id, current_resolution_cycle_id
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'IN_PROGRESS',
            escalation_resumed_at = :resumedAt,
            escalation_resumed_by = :resumedBy,
            escalation_resume_reason_code = :resumeReasonCode,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'ESCALATED'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketEscalationResumePersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketEscalationResumeGuard> loadGuard(TicketId ticketId) {
        List<TicketEscalationResumeGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new TicketEscalationResumeGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getString("current_team_id"),
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
    public TicketEscalationResumeUpdateOutcome applyResume(TicketEscalationResumeUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("resumedAt", Timestamp.from(update.resumedAt()))
            .addValue("resumedBy", update.resumedById())
            .addValue("resumeReasonCode", update.resumeReasonCode().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion());

        int rowsAffected = namedParameterJdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (rowsAffected != 1) {
            return reclassify(update);
        }

        return new TicketEscalationResumeUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketEscalationResumeUpdateOutcome reclassify(TicketEscalationResumeUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketEscalationResumeUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketEscalationResumeUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketEscalationResumeUpdateOutcome.InvalidState(currentStatus);
    }
}
