package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-014 persistence: the ticket-row update and the new open
 * approval-request insert, executed as two statements against the same
 * Spring-managed transaction (mirrors {@code
 * TicketUserInputRequestPersistenceAdapter}, SPEC-TW-012). The partial
 * unique index ({@code uq_ticket_one_open_approval_request}) is
 * defense-in-depth only — the ticket UPDATE's {@code status = 'IN_PROGRESS'}
 * guard already prevents two concurrent commands from both reaching the
 * INSERT for the same ticket.
 */
@Component
public class TicketApprovalRequestPersistenceAdapter implements TicketApprovalRequestRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'WAITING_FOR_APPROVAL',
            approval_reference = :approvalId,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'IN_PROGRESS'
          AND current_support_user_id IS NOT NULL
        """;

    private static final String INSERT_REQUEST_SQL = """
        INSERT INTO ticket.ticket_approval_requests
            (approval_request_id, ticket_id, approval_id, workflow_id, action_id, action_type, request_status,
             risk_level, risk_context, reason, requested_by_type, requested_by_id, requested_at, correlation_id)
        VALUES (:approvalRequestId, :ticketId, :approvalId, :workflowId, :actionId, :actionType, 'OPEN',
                :riskLevel, CAST(:riskContext AS jsonb), :reason, :requestedByType, :requestedById, :requestedAt, :correlationId)
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketApprovalRequestPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public TicketApprovalRequestUpdateOutcome applyRequestApproval(TicketApprovalRequestUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("approvalRequestId", update.approvalRequestId())
            .addValue("approvalId", update.approvalId())
            .addValue("workflowId", update.workflowId())
            .addValue("actionId", update.actionId())
            .addValue("actionType", update.actionType())
            .addValue("riskLevel", update.riskLevel())
            .addValue("riskContext", writeRiskContext(update.riskContext()))
            .addValue("reason", update.reason())
            .addValue("requestedByType", update.requestedByType())
            .addValue("requestedById", update.requestedById())
            .addValue("requestedAt", Timestamp.from(update.requestedAt()))
            .addValue("correlationId", update.correlationId())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion());

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        try {
            jdbcTemplate.update(INSERT_REQUEST_SQL, params);
        } catch (DataIntegrityViolationException e) {
            return new TicketApprovalRequestUpdateOutcome.RequestAlreadyOpen();
        }

        return new TicketApprovalRequestUpdateOutcome.Created(update.expectedVersion() + 1);
    }

    private String writeRiskContext(Map<String, Object> riskContext) {
        try {
            return objectMapper.writeValueAsString(riskContext);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize riskContext", e);
        }
    }

    private TicketApprovalRequestUpdateOutcome reclassify(TicketApprovalRequestUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketApprovalRequestUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new TicketApprovalRequestUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new TicketApprovalRequestUpdateOutcome.NotAssigned();
        }
        return new TicketApprovalRequestUpdateOutcome.InvalidState(currentStatus);
    }
}
