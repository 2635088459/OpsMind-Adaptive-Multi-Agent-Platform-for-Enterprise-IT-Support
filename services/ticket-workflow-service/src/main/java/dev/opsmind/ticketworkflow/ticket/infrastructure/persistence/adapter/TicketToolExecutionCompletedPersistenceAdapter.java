package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuardPort;
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
 * SPEC-TW-019 persistence: the guard read finds the most recently decided
 * {@code GRANTED}/{@code AUTO_APPROVED} approval request for this ticket,
 * workflow, and action — that row's {@code authorization_reference} is the
 * durable authorization a {@code tool.execution.completed.v1} event must
 * match, since {@code tickets.approval_reference} itself is cleared back to
 * {@code NULL} the moment a grant/auto-approval is applied (SPEC-TW-015 §
 * TicketApprovalGrantedPersistenceAdapter). The write path updates the
 * ticket row first, guarded by status={@code EXECUTING} plus version — a
 * 0-row update means the guard-time state already changed concurrently,
 * reclassified {@code STALE} by the caller — then inserts the result row.
 * {@code tool_execution_id} is the table's primary key, so a concurrent
 * duplicate delivery that raced past the application layer's own
 * existence check still cannot double-insert; that race is caught here and
 * folded into the same {@code Conflict} outcome.
 */
@Component
public class TicketToolExecutionCompletedPersistenceAdapter implements TicketToolExecutionGuardPort, TicketToolExecutionCompletedRepository {

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
        SET status = 'VERIFYING',
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'EXECUTING'
        """;

    private static final String INSERT_RESULT_SQL = """
        INSERT INTO ticket.ticket_tool_execution_results
            (tool_execution_id, ticket_id, workflow_id, action_id, authorization_reference,
             result_status, tool_result_id, completed_at, result_summary, event_id, recorded_at)
        VALUES
            (:toolExecutionId, :ticketId, :workflowId, :actionId, :authorizationReference,
             'COMPLETED', :toolResultId, :completedAt, :resultSummary, :eventId, :recordedAt)
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketToolExecutionCompletedPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
    public TicketToolExecutionCompletedUpdateOutcome applyToolExecutionCompleted(TicketToolExecutionCompletedUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketToolExecutionCompletedUpdateOutcome.Conflict();
        }

        MapSqlParameterSource resultParams = new MapSqlParameterSource()
            .addValue("toolExecutionId", update.toolExecutionId())
            .addValue("ticketId", update.ticketId().value())
            .addValue("workflowId", update.workflowId())
            .addValue("actionId", update.actionId())
            .addValue("authorizationReference", update.authorizationReference())
            .addValue("toolResultId", update.toolResultId())
            .addValue("completedAt", Timestamp.from(update.completedAt()))
            .addValue("resultSummary", writeResultSummary(update.resultSummary()))
            .addValue("eventId", update.eventId())
            .addValue("recordedAt", Timestamp.from(update.updatedAt()));

        try {
            jdbcTemplate.update(INSERT_RESULT_SQL, resultParams);
        } catch (DuplicateKeyException e) {
            return new TicketToolExecutionCompletedUpdateOutcome.Conflict();
        }

        return new TicketToolExecutionCompletedUpdateOutcome.Applied(update.expectedVersion() + 1);
    }

    private String writeResultSummary(Map<String, Object> resultSummary) {
        if (resultSummary == null || resultSummary.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(resultSummary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize tool execution result summary", e);
        }
    }
}
