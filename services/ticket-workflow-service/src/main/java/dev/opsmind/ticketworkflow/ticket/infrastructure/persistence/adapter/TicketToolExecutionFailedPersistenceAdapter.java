package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-020 persistence: mirrors {@code
 * TicketToolExecutionCompletedPersistenceAdapter}'s (SPEC-TW-019) guard read
 * exactly — same query, same reasoning for why the authorizing approval
 * request, not {@code tickets.approval_reference}, is the durable
 * authorization source — kept as its own class/bean per this codebase's
 * one-adapter-per-spec convention. The write path's target status
 * ({@code IN_PROGRESS} for a known-safe failure, {@code FAILED} for a
 * pipeline failure) is supplied by the caller, already computed by {@code
 * Ticket.applyToolExecutionFailed}, rather than hardcoded here.
 */
@Component
public class TicketToolExecutionFailedPersistenceAdapter implements TicketToolExecutionFailureGuardPort, TicketToolExecutionFailedRepository {

    private static final String GUARD_SQL = """
        SELECT ar.approval_request_id, ar.action_type, ar.authorization_reference,
               t.display_id, t.status AS ticket_status, t.version AS ticket_version,
               t.support_queue_id, t.current_support_user_id
        FROM ticket.ticket_approval_requests ar
        JOIN ticket.tickets t ON t.ticket_id = ar.ticket_id
        WHERE ar.ticket_id = :ticketId AND ar.workflow_id = :workflowId AND ar.action_id = :actionId
          AND ar.request_status IN ('GRANTED', 'AUTO_APPROVED')
        ORDER BY COALESCE(ar.approved_at, ar.auto_approved_at) DESC
        LIMIT 1
        """;

    private static final String EXISTS_SQL = """
        SELECT 1 FROM ticket.ticket_tool_execution_results WHERE tool_execution_id = :toolExecutionId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = :newStatus,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'EXECUTING'
        """;

    private static final String INSERT_RESULT_SQL = """
        INSERT INTO ticket.ticket_tool_execution_results
            (tool_execution_id, ticket_id, workflow_id, action_id, authorization_reference,
             result_status, failure_code, failure_class, failed_at, safe_to_retry, event_id, recorded_at)
        VALUES
            (:toolExecutionId, :ticketId, :workflowId, :actionId, :authorizationReference,
             'FAILED', :failureCode, :failureClass, :failedAt, :safeToRetry, :eventId, :recordedAt)
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketToolExecutionFailedPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketToolExecutionGuard> loadGuard(TicketId ticketId, String workflowId, String actionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            GUARD_SQL,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId.value())
                .addValue("workflowId", workflowId)
                .addValue("actionId", actionId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID supportQueueId = (UUID) row.get("support_queue_id");
        return Optional.of(new TicketToolExecutionGuard(
            ticketId,
            TicketDisplayId.of((String) row.get("display_id")),
            TicketStatus.valueOf((String) row.get("ticket_status")),
            ((Number) row.get("ticket_version")).longValue(),
            supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
            (String) row.get("current_support_user_id"),
            (UUID) row.get("approval_request_id"),
            (String) row.get("action_type"),
            (String) row.get("authorization_reference")
        ));
    }

    @Override
    public boolean existsByToolExecutionId(String toolExecutionId) {
        List<Integer> rows = jdbcTemplate.queryForList(
            EXISTS_SQL, new MapSqlParameterSource().addValue("toolExecutionId", toolExecutionId), Integer.class
        );
        return !rows.isEmpty();
    }

    @Override
    public TicketToolExecutionFailedUpdateOutcome applyToolExecutionFailed(TicketToolExecutionFailedUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("newStatus", update.newStatus().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketToolExecutionFailedUpdateOutcome.Conflict();
        }

        MapSqlParameterSource resultParams = new MapSqlParameterSource()
            .addValue("toolExecutionId", update.toolExecutionId())
            .addValue("ticketId", update.ticketId().value())
            .addValue("workflowId", update.workflowId())
            .addValue("actionId", update.actionId())
            .addValue("authorizationReference", update.authorizationReference())
            .addValue("failureCode", update.failureCode())
            .addValue("failureClass", update.failureClass())
            .addValue("failedAt", Timestamp.from(update.failedAt()))
            .addValue("safeToRetry", update.safeToRetry())
            .addValue("eventId", update.eventId())
            .addValue("recordedAt", Timestamp.from(update.updatedAt()));

        try {
            jdbcTemplate.update(INSERT_RESULT_SQL, resultParams);
        } catch (DuplicateKeyException e) {
            return new TicketToolExecutionFailedUpdateOutcome.Conflict();
        }

        return new TicketToolExecutionFailedUpdateOutcome.Applied(update.expectedVersion() + 1);
    }
}
