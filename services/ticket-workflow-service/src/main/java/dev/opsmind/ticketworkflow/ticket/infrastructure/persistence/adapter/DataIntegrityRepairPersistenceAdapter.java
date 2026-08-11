package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.model.DataIntegrityRepairRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairRepository;
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
 * SPEC-TW-041 persistence: {@link #loadTargetCase} resolves the ticket by
 * joining {@code ticket.ticket_phase10_open_reconciliation_case} (the
 * SPEC-TW-037 recovery entry point every Phase 10 finding is expected to
 * have opened a case through) on its primary key with {@code
 * ticket.tickets}. A {@code sourceReference} that is not a well-formed
 * {@link UUID} is treated identically to "not found" (mirrors {@code
 * TicketNotFoundException}'s own deliberate non-disclosure). {@link
 * #summarize}/{@link #record} mirror {@code ReplayEventPersistenceAdapter}
 * (SPEC-TW-038) exactly, against {@code
 * ticket_phase10_data_integrity_repair} instead.
 */
@Component
public class DataIntegrityRepairPersistenceAdapter implements DataIntegrityRepairGuardPort, DataIntegrityRepairRepository {

    private static final String GUARD_SQL = """
        SELECT t.ticket_id, t.display_id, t.support_queue_id
        FROM ticket.ticket_phase10_open_reconciliation_case c
        JOIN ticket.tickets t ON t.ticket_id = c.ticket_id
        WHERE c.id = ?
        """;

    private static final String SUMMARIZE_SQL = """
        SELECT COUNT(*) AS total_attempts,
               COUNT(*) FILTER (WHERE completed_at IS NULL) AS open_attempts
        FROM ticket.ticket_phase10_data_integrity_repair
        WHERE ticket_id = :ticketId
          AND source_reference = :sourceReference
        """;

    private static final String INSERT_SQL = """
        INSERT INTO ticket.ticket_phase10_data_integrity_repair (
            id, ticket_id, source_reference, decision, reason_code, reason,
            actor_id, correlation_id, causation_id, attempt_number, created_at, completed_at
        ) VALUES (
            :id, :ticketId, :sourceReference, :decision, :reasonCode, :reason,
            :actorId, :correlationId, :causationId, :attemptNumber, :createdAt, :completedAt
        )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public DataIntegrityRepairPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<DataIntegrityRepairGuard> loadTargetCase(String sourceReference) {
        UUID caseId;
        try {
            caseId = UUID.fromString(sourceReference);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        List<DataIntegrityRepairGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                return new DataIntegrityRepairGuard(
                    TicketId.of((UUID) rs.getObject("ticket_id")),
                    TicketDisplayId.of(rs.getString("display_id")),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId)
                );
            },
            caseId
        );
        return rows.stream().findFirst();
    }

    @Override
    public DataIntegrityRepairAttemptSummary summarize(TicketId ticketId, String sourceReference) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("sourceReference", sourceReference);

        return namedParameterJdbcTemplate.queryForObject(SUMMARIZE_SQL, params, (rs, rowNum) ->
            new DataIntegrityRepairAttemptSummary(rs.getInt("total_attempts"), rs.getInt("open_attempts") > 0)
        );
    }

    @Override
    public void record(DataIntegrityRepairRecord record) {
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
