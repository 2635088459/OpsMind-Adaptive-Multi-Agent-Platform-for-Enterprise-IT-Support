package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.ReplayEventRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventRepository;
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
 * SPEC-TW-038 persistence: {@link #loadOriginalEvent} resolves the ticket by
 * joining {@code ticket.outbox_events} (the only queryable "original event"
 * store this codebase persists — README §"Goal" also names consumer inbox
 * and DLQ messages, neither of which has a table here) on its unique {@code
 * event_id} with {@code ticket.tickets}. {@link #summarize}/{@link #record}
 * mirror {@code ReconciliationCasePersistenceAdapter} (SPEC-TW-037) exactly,
 * against {@code ticket_phase10_replay_event} instead.
 */
@Component
public class ReplayEventPersistenceAdapter implements ReplayEventGuardPort, ReplayEventRepository {

    private static final String GUARD_SQL = """
        SELECT t.ticket_id, t.display_id, t.support_queue_id
        FROM ticket.outbox_events o
        JOIN ticket.tickets t ON t.ticket_id = o.ticket_id
        WHERE o.event_id = ?
        """;

    private static final String SUMMARIZE_SQL = """
        SELECT COUNT(*) AS total_attempts,
               COUNT(*) FILTER (WHERE completed_at IS NULL) AS open_attempts
        FROM ticket.ticket_phase10_replay_event
        WHERE ticket_id = :ticketId
          AND source_reference = :sourceReference
        """;

    private static final String INSERT_SQL = """
        INSERT INTO ticket.ticket_phase10_replay_event (
            id, ticket_id, source_reference, decision, reason_code, reason,
            actor_id, correlation_id, causation_id, attempt_number, created_at, completed_at
        ) VALUES (
            :id, :ticketId, :sourceReference, :decision, :reasonCode, :reason,
            :actorId, :correlationId, :causationId, :attemptNumber, :createdAt, :completedAt
        )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public ReplayEventPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<ReplayEventGuard> loadOriginalEvent(String sourceReference) {
        List<ReplayEventGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new ReplayEventGuard(
                    TicketId.of((UUID) rs.getObject("ticket_id")),
                    TicketDisplayId.of(rs.getString("display_id")),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId)
                );
            },
            sourceReference
        );
        return rows.stream().findFirst();
    }

    @Override
    public ReplayEventAttemptSummary summarize(TicketId ticketId, String sourceReference) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("sourceReference", sourceReference);

        return namedParameterJdbcTemplate.queryForObject(SUMMARIZE_SQL, params, (rs, rowNum) ->
            new ReplayEventAttemptSummary(rs.getInt("total_attempts"), rs.getInt("open_attempts") > 0)
        );
    }

    @Override
    public void record(ReplayEventRecord record) {
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
