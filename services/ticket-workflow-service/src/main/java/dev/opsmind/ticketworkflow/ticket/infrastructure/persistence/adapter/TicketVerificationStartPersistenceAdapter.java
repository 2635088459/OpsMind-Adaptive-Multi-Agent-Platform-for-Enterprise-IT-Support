package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.CompletedToolResultReference;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-022 persistence: the ticket-row update is a self-transition
 * ({@code status} stays {@code VERIFYING}), guarded by version/status/
 * assignee exactly like {@code TicketApprovalRequestPersistenceAdapter}'s
 * (SPEC-TW-014) write path, followed by the new attempt insert in the same
 * transaction. {@code uq_verification_one_active_tool_result} is the
 * authoritative "one active attempt per tool result" guard; {@link
 * TicketVerificationStartRepository#hasActiveAttempt} is a pre-check that
 * exists purely to return a clean conflict before ever touching the ticket
 * row.
 */
@Component
public class TicketVerificationStartPersistenceAdapter implements TicketVerificationStartGuardPort, TicketVerificationStartRepository {

    private static final String GUARD_SQL = """
        SELECT display_id, status, version, support_queue_id, current_support_user_id, current_resolution_cycle_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private static final String FIND_COMPLETED_TOOL_RESULT_SQL = """
        SELECT workflow_id FROM ticket.ticket_tool_execution_results
        WHERE ticket_id = :ticketId AND tool_result_id = :toolResultId AND result_status = 'COMPLETED'
        ORDER BY recorded_at DESC
        LIMIT 1
        """;

    private static final String HAS_ACTIVE_ATTEMPT_SQL = """
        SELECT 1 FROM ticket.ticket_verification_attempts WHERE tool_result_id = :toolResultId AND attempt_status = 'ACTIVE'
        """;

    private static final String NEXT_ATTEMPT_NUMBER_SQL = """
        SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM ticket.ticket_verification_attempts WHERE tool_result_id = :toolResultId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'VERIFYING',
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'VERIFYING'
          AND current_support_user_id IS NOT NULL
        """;

    private static final String INSERT_ATTEMPT_SQL = """
        INSERT INTO ticket.ticket_verification_attempts
            (verification_id, ticket_id, resolution_cycle_id, workflow_id, tool_result_id, attempt_number,
             attempt_status, verification_type, started_at, event_id)
        VALUES
            (:verificationId, :ticketId, :resolutionCycleId, :workflowId, :toolResultId, :attemptNumber,
             'ACTIVE', :verificationType, :startedAt, NULL)
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketVerificationStartPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketVerificationStartGuard> loadGuard(TicketId ticketId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            GUARD_SQL, new MapSqlParameterSource().addValue("ticketId", ticketId.value())
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID supportQueueId = (UUID) row.get("support_queue_id");
        return Optional.of(new TicketVerificationStartGuard(
            ticketId,
            TicketDisplayId.of((String) row.get("display_id")),
            TicketStatus.valueOf((String) row.get("status")),
            ((Number) row.get("version")).longValue(),
            supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
            (String) row.get("current_support_user_id"),
            (UUID) row.get("current_resolution_cycle_id")
        ));
    }

    @Override
    public Optional<CompletedToolResultReference> findCompletedToolResult(TicketId ticketId, String toolResultId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            FIND_COMPLETED_TOOL_RESULT_SQL,
            new MapSqlParameterSource().addValue("ticketId", ticketId.value()).addValue("toolResultId", toolResultId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CompletedToolResultReference((String) rows.get(0).get("workflow_id")));
    }

    @Override
    public boolean hasActiveAttempt(String toolResultId) {
        List<Integer> rows = jdbcTemplate.queryForList(
            HAS_ACTIVE_ATTEMPT_SQL, new MapSqlParameterSource().addValue("toolResultId", toolResultId), Integer.class
        );
        return !rows.isEmpty();
    }

    @Override
    public int nextAttemptNumber(String toolResultId) {
        Integer next = jdbcTemplate.queryForObject(
            NEXT_ATTEMPT_NUMBER_SQL, new MapSqlParameterSource().addValue("toolResultId", toolResultId), Integer.class
        );
        return next == null ? 1 : next;
    }

    @Override
    public TicketVerificationStartUpdateOutcome startVerification(TicketVerificationStartUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        MapSqlParameterSource attemptParams = new MapSqlParameterSource()
            .addValue("verificationId", update.verificationId())
            .addValue("ticketId", update.ticketId().value())
            .addValue("resolutionCycleId", update.resolutionCycleId())
            .addValue("workflowId", update.workflowId())
            .addValue("toolResultId", update.toolResultId())
            .addValue("attemptNumber", update.attemptNumber())
            .addValue("verificationType", update.verificationType())
            .addValue("startedAt", Timestamp.from(update.startedAt()));

        try {
            jdbcTemplate.update(INSERT_ATTEMPT_SQL, attemptParams);
        } catch (DataIntegrityViolationException e) {
            return new TicketVerificationStartUpdateOutcome.AttemptAlreadyActive();
        }

        return new TicketVerificationStartUpdateOutcome.Created(update.expectedVersion() + 1);
    }

    private TicketVerificationStartUpdateOutcome reclassify(TicketVerificationStartUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketVerificationStartUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new TicketVerificationStartUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new TicketVerificationStartUpdateOutcome.NotAssigned();
        }
        return new TicketVerificationStartUpdateOutcome.InvalidState(currentStatus);
    }
}
