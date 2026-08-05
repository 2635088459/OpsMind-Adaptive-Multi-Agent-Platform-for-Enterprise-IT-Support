package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsert;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsertOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyRepository;
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
 * SPEC-TW-018 persistence: structurally mirrors {@code
 * TicketApprovalRequestPersistenceAdapter}'s (SPEC-TW-014) guard-then-insert
 * shape rather than SPEC-TW-015/016/017's guard-then-update shape, since
 * auto-approval mints a brand new {@code ticket_approval_requests} row
 * instead of resolving an existing open one. The guard read {@code LEFT
 * JOIN}s the request table on the shared {@code approval_id} column (here
 * storing the event's {@code policyDecisionId}) so a prior delivery is
 * detected up front without a second round trip; a race between two
 * concurrent first-deliveries is still caught at INSERT time by the same
 * column's {@code uq_approval_request_approval_id} unique index via {@link
 * DataIntegrityViolationException}. The ticket-row update touches only
 * {@code version}/{@code updated_at} — {@code status} and {@code
 * approval_reference} are deliberately left alone, since SPEC-TW-018 never
 * moves the ticket out of {@code IN_PROGRESS}.
 */
@Component
public class TicketAutoApprovedPolicyPersistenceAdapter implements TicketAutoApprovedPolicyGuardPort, TicketAutoApprovedPolicyRepository {

    private static final String GUARD_SQL = """
        SELECT t.ticket_id, t.display_id, t.status AS ticket_status, t.version AS ticket_version,
               t.support_queue_id, t.current_support_user_id,
               ar.approval_request_id AS existing_approval_request_id
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_approval_requests ar ON ar.approval_id = :policyDecisionId
        WHERE t.ticket_id = :ticketId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'IN_PROGRESS'
        """;

    private static final String INSERT_REQUEST_SQL = """
        INSERT INTO ticket.ticket_approval_requests
            (approval_request_id, ticket_id, approval_id, workflow_id, action_id, action_type, request_status,
             risk_level, risk_context, requested_by_type, requested_by_id, requested_at,
             policy_decision_id, policy_id, policy_version, auto_approved_at, auto_approval_event_id, authorization_reference)
        VALUES (:approvalRequestId, :ticketId, :policyDecisionId, :workflowId, :actionId, :actionType, 'AUTO_APPROVED',
                :riskLevel, CAST(:riskContext AS jsonb), 'SERVICE', 'policy-approval-service', :decidedAt,
                :policyDecisionId, :policyId, :policyVersion, :decidedAt, :autoApprovalEventId, :authorizationReference)
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketAutoApprovedPolicyPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TicketAutoApprovedPolicyGuard> loadGuard(TicketId ticketId, String policyDecisionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            GUARD_SQL, new MapSqlParameterSource().addValue("ticketId", ticketId.value()).addValue("policyDecisionId", policyDecisionId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID supportQueueId = (UUID) row.get("support_queue_id");
        return Optional.of(new TicketAutoApprovedPolicyGuard(
            ticketId,
            TicketDisplayId.of((String) row.get("display_id")),
            TicketStatus.valueOf((String) row.get("ticket_status")),
            ((Number) row.get("ticket_version")).longValue(),
            supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
            (String) row.get("current_support_user_id"),
            (UUID) row.get("existing_approval_request_id")
        ));
    }

    @Override
    public TicketAutoApprovedPolicyInsertOutcome applyAutoApprovedPolicy(TicketAutoApprovedPolicyInsert insert) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", insert.ticketId().value())
            .addValue("expectedVersion", insert.expectedVersion())
            .addValue("updatedAt", Timestamp.from(insert.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketAutoApprovedPolicyInsertOutcome.TicketConflict();
        }

        MapSqlParameterSource requestParams = new MapSqlParameterSource()
            .addValue("approvalRequestId", insert.approvalRequestId())
            .addValue("ticketId", insert.ticketId().value())
            .addValue("policyDecisionId", insert.policyDecisionId())
            .addValue("workflowId", insert.workflowId())
            .addValue("actionId", insert.actionId())
            .addValue("actionType", insert.actionType())
            .addValue("riskLevel", insert.riskLevel())
            .addValue("riskContext", writeRiskContext(insert.riskContext()))
            .addValue("decidedAt", Timestamp.from(insert.decidedAt()))
            .addValue("policyId", insert.policyId())
            .addValue("policyVersion", insert.policyVersion())
            .addValue("autoApprovalEventId", insert.autoApprovalEventId())
            .addValue("authorizationReference", insert.authorizationReference());

        try {
            jdbcTemplate.update(INSERT_REQUEST_SQL, requestParams);
        } catch (DataIntegrityViolationException e) {
            return new TicketAutoApprovedPolicyInsertOutcome.DuplicateConflict();
        }

        return new TicketAutoApprovedPolicyInsertOutcome.Applied(insert.expectedVersion() + 1);
    }

    private String writeRiskContext(Map<String, Object> riskContext) {
        try {
            return objectMapper.writeValueAsString(riskContext);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize riskContext", e);
        }
    }
}
