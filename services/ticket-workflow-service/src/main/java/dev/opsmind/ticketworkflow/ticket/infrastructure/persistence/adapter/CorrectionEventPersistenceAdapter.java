package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.CorrectionEventRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-039 persistence: the guard read is a plain existence lookup on
 * {@code ticket.tickets} (mirrors {@code ReconciliationCasePersistenceAdapter},
 * SPEC-TW-037). {@link #summarize} counts every prior {@code
 * ticket_phase10_correction_event} attempt for the same {@code (ticketId,
 * sourceReference)} pair in one aggregate query, using {@code FILTER} to
 * also detect whether one is still open.
 */
@Component
public class CorrectionEventPersistenceAdapter implements TicketCorrectionEventGuardPort, CorrectionEventRepository {

    private static final String GUARD_SQL = """
        SELECT display_id, support_queue_id
        FROM ticket.tickets
        WHERE ticket_id = ?
        """;

    private static final String SUMMARIZE_SQL = """
        SELECT COUNT(*) AS total_attempts,
               COUNT(*) FILTER (WHERE completed_at IS NULL) AS open_attempts
        FROM ticket.ticket_phase10_correction_event
        WHERE ticket_id = :ticketId
          AND source_reference = :sourceReference
        """;

    private static final String INSERT_SQL = """
        INSERT INTO ticket.ticket_phase10_correction_event (
            id, ticket_id, source_reference, decision, reason_code, reason,
            actor_id, correlation_id, causation_id, attempt_number, created_at, completed_at
        ) VALUES (
            :id, :ticketId, :sourceReference, :decision, :reasonCode, :reason,
            :actorId, :correlationId, :causationId, :attemptNumber, :createdAt, :completedAt
        )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public CorrectionEventPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<TicketCorrectionEventGuard> loadGuard(TicketId ticketId) {
        List<TicketCorrectionEventGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new TicketCorrectionEventGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId)
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public CorrectionEventAttemptSummary summarize(TicketId ticketId, String sourceReference) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("sourceReference", sourceReference);

        return namedParameterJdbcTemplate.queryForObject(SUMMARIZE_SQL, params, (rs, rowNum) ->
            new CorrectionEventAttemptSummary(rs.getInt("total_attempts"), rs.getInt("open_attempts") > 0)
        );
    }

    @Override
    public void record(CorrectionEventRecord record) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", record.id())
            .addValue("ticketId", record.ticketId().value())
            .addValue("sourceReference", record.sourceReference())
            .addValue("decision", record.decision().name())
            .addValue("reasonCode", record.reasonCode().name())
            .addValue("reason", record.reason())
            .addValue("actorId", record.actorId())
            .addValue("correlationId", record.correlationId())
            .addValue("causationId", record.causationId())
            .addValue("attemptNumber", record.attemptNumber())
            .addValue("createdAt", Timestamp.from(record.createdAt()))
            .addValue("completedAt", record.completedAt() == null ? null : Timestamp.from(record.completedAt()));

        namedParameterJdbcTemplate.update(INSERT_SQL, params);
    }
}
