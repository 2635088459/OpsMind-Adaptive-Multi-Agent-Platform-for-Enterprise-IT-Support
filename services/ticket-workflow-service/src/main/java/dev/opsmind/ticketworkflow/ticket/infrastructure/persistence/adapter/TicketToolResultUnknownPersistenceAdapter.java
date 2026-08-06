package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionExistingRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdateOutcome;
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
 * SPEC-TW-021 persistence: the guard read mirrors {@code
 * TicketToolExecutionCompletedPersistenceAdapter}'s (SPEC-TW-019) exactly,
 * kept as its own class/bean per this codebase's one-adapter-per-spec
 * convention. {@link #findExisting} and {@link
 * #markConflictRequiresReconciliation} are the two methods unique to this
 * spec — SPEC-TW-019/020 only need a boolean existence check, but SPEC-021
 * must additionally tell a plain {@code UNKNOWN} replay apart from a
 * {@code COMPLETED}/{@code FAILED} row that must never be overwritten.
 */
@Component
public class TicketToolResultUnknownPersistenceAdapter implements TicketToolResultUnknownGuardPort, TicketToolResultUnknownRepository {

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

    private static final String FIND_EXISTING_SQL = """
        SELECT ticket_id, result_status FROM ticket.ticket_tool_execution_results WHERE tool_execution_id = :toolExecutionId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'ESCALATED',
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'EXECUTING'
        """;

    private static final String INSERT_RESULT_SQL = """
        INSERT INTO ticket.ticket_tool_execution_results
            (tool_execution_id, ticket_id, workflow_id, action_id, authorization_reference,
             result_status, unknown_reason, observed_at, evidence_references, reconciliation_required,
             event_id, recorded_at)
        VALUES
            (:toolExecutionId, :ticketId, :workflowId, :actionId, :authorizationReference,
             'UNKNOWN', :unknownReason, :observedAt, CAST(:evidenceReferences AS jsonb), TRUE,
             :eventId, :recordedAt)
        """;

    private static final String MARK_CONFLICT_SQL = """
        UPDATE ticket.ticket_tool_execution_results
        SET reconciliation_required = TRUE,
            conflict_event_id = :conflictEventId
        WHERE tool_execution_id = :toolExecutionId AND ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketToolResultUnknownPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
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
    public Optional<TicketToolExecutionExistingRecord> findExisting(String toolExecutionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            FIND_EXISTING_SQL, new MapSqlParameterSource().addValue("toolExecutionId", toolExecutionId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new TicketToolExecutionExistingRecord((UUID) row.get("ticket_id"), (String) row.get("result_status")));
    }

    @Override
    public TicketToolResultUnknownUpdateOutcome recordUnknownResult(TicketToolResultUnknownUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketToolResultUnknownUpdateOutcome.Conflict();
        }

        MapSqlParameterSource resultParams = new MapSqlParameterSource()
            .addValue("toolExecutionId", update.toolExecutionId())
            .addValue("ticketId", update.ticketId().value())
            .addValue("workflowId", update.workflowId())
            .addValue("actionId", update.actionId())
            .addValue("authorizationReference", update.authorizationReference())
            .addValue("unknownReason", update.unknownReason())
            .addValue("observedAt", Timestamp.from(update.observedAt()))
            .addValue("evidenceReferences", writeEvidenceReferences(update.evidenceReferences()))
            .addValue("eventId", update.eventId())
            .addValue("recordedAt", Timestamp.from(update.updatedAt()));

        try {
            jdbcTemplate.update(INSERT_RESULT_SQL, resultParams);
        } catch (DuplicateKeyException e) {
            return new TicketToolResultUnknownUpdateOutcome.Conflict();
        }

        return new TicketToolResultUnknownUpdateOutcome.Applied(update.expectedVersion() + 1);
    }

    @Override
    public boolean markConflictRequiresReconciliation(TicketId ticketId, String toolExecutionId, String conflictEventId) {
        int rowsAffected = jdbcTemplate.update(MARK_CONFLICT_SQL, new MapSqlParameterSource()
            .addValue("toolExecutionId", toolExecutionId)
            .addValue("ticketId", ticketId.value())
            .addValue("conflictEventId", conflictEventId)
        );
        return rowsAffected == 1;
    }

    private String writeEvidenceReferences(List<String> evidenceReferences) {
        if (evidenceReferences == null || evidenceReferences.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidenceReferences);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize tool execution unknown-result evidence references", e);
        }
    }
}
