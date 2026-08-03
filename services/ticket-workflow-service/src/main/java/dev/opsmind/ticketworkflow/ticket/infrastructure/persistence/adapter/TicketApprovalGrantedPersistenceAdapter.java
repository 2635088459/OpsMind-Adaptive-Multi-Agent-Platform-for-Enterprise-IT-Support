package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-015 persistence: the guard read joins the open-approval-request
 * row with its ticket so the Application layer can classify duplicate/
 * stale/reference-mismatch without a second round trip (mirrors {@code
 * TicketStatusTransitionGuardAdapter}'s single-query-projection shape). The
 * write path updates the ticket row first, guarded by {@code
 * approval_reference = :approvalId} in addition to status/version — this
 * ties the ticket-level guard to the exact approval this event grants, not
 * merely "some" open approval — then marks the approval request {@code
 * GRANTED}. A ticket-row update failure (0 rows) means the guard-time state
 * already changed concurrently; the caller reclassifies that as {@code
 * STALE}. An approval-request update failure after the ticket update
 * already succeeded would mean the two rows disagree despite the guard
 * query having just observed them consistent — that violates this schema's
 * own invariants, so it is a fail-fast bug worth investigating rather than
 * a business outcome to swallow.
 */
@Component
public class TicketApprovalGrantedPersistenceAdapter implements TicketApprovalGrantGuardPort, TicketApprovalGrantedRepository {

    private static final String GUARD_SQL = """
        SELECT ar.approval_request_id, ar.request_status, ar.workflow_id, ar.action_id, ar.action_type,
               t.display_id, t.status AS ticket_status, t.version AS ticket_version,
               t.support_queue_id, t.current_support_user_id
        FROM ticket.ticket_approval_requests ar
        JOIN ticket.tickets t ON t.ticket_id = ar.ticket_id
        WHERE ar.ticket_id = :ticketId AND ar.approval_id = :approvalId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'IN_PROGRESS',
            approval_reference = NULL,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'WAITING_FOR_APPROVAL'
          AND approval_reference = :approvalId
        """;

    private static final String UPDATE_APPROVAL_REQUEST_SQL = """
        UPDATE ticket.ticket_approval_requests
        SET request_status = 'GRANTED',
            approved_by = :approvedBy,
            approved_at = :approvedAt,
            authorization_reference = :authorizationReference,
            granted_event_id = :grantedEventId
        WHERE approval_request_id = :approvalRequestId
          AND request_status = 'OPEN'
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketApprovalGrantedPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketApprovalGrantGuard> loadGuard(TicketId ticketId, String approvalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            GUARD_SQL, new MapSqlParameterSource().addValue("ticketId", ticketId.value()).addValue("approvalId", approvalId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID supportQueueId = (UUID) row.get("support_queue_id");
        return Optional.of(new TicketApprovalGrantGuard(
            ticketId,
            TicketDisplayId.of((String) row.get("display_id")),
            TicketStatus.valueOf((String) row.get("ticket_status")),
            ((Number) row.get("ticket_version")).longValue(),
            supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
            (String) row.get("current_support_user_id"),
            (UUID) row.get("approval_request_id"),
            (String) row.get("request_status"),
            (String) row.get("workflow_id"),
            (String) row.get("action_id"),
            (String) row.get("action_type")
        ));
    }

    @Override
    public TicketApprovalGrantedUpdateOutcome applyApprovalGranted(TicketApprovalGrantedUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("approvalId", update.approvalId())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketApprovalGrantedUpdateOutcome.Conflict();
        }

        MapSqlParameterSource requestParams = new MapSqlParameterSource()
            .addValue("approvalRequestId", update.approvalRequestId())
            .addValue("approvedBy", update.approvedBy())
            .addValue("approvedAt", Timestamp.from(update.approvedAt()))
            .addValue("authorizationReference", update.authorizationReference())
            .addValue("grantedEventId", update.grantedEventId());

        int requestRowsAffected = jdbcTemplate.update(UPDATE_APPROVAL_REQUEST_SQL, requestParams);
        if (requestRowsAffected != 1) {
            throw new IllegalStateException(
                "approval request " + update.approvalRequestId() + " was not OPEN at write time despite the guard read observing it OPEN"
            );
        }

        return new TicketApprovalGrantedUpdateOutcome.Applied(update.expectedVersion() + 1);
    }
}
