package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdateOutcome;
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
 * SPEC-TW-023 persistence: the guard read joins the verification attempt
 * (SPEC-TW-022) with its ticket so the Application layer can classify
 * duplicate/stale/conflict without a second round trip. The write path
 * updates the ticket row first — a self-transition guarded by version and
 * {@code status = 'VERIFYING'}, mirroring {@code
 * TicketVerificationStartPersistenceAdapter}'s (SPEC-TW-022) write shape —
 * then marks the attempt {@code SUCCEEDED}. A 0-row ticket update means the
 * guard-time state already changed concurrently, reclassified {@code
 * STALE} by the caller; that same row-lock ordering means a genuinely
 * concurrent duplicate/conflicting delivery is always caught by the
 * version guard before it could ever race the attempt update itself (see
 * {@code TicketToolExecutionCompletedPersistenceAdapter}'s Javadoc for the
 * full reasoning, which applies identically here).
 */
@Component
public class TicketVerificationSuccessPersistenceAdapter implements TicketVerificationSuccessGuardPort, TicketVerificationSuccessRepository {

    private static final String GUARD_SQL = """
        SELECT va.ticket_id, va.workflow_id, va.resolution_cycle_id, va.attempt_number, va.attempt_status,
               t.display_id, t.status AS ticket_status, t.version AS ticket_version, t.support_queue_id,
               t.current_support_user_id, t.current_resolution_cycle_id
        FROM ticket.ticket_verification_attempts va
        JOIN ticket.tickets t ON t.ticket_id = va.ticket_id
        WHERE va.verification_id = :verificationId
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'VERIFYING'
        """;

    private static final String UPDATE_ATTEMPT_SQL = """
        UPDATE ticket.ticket_verification_attempts
        SET attempt_status = 'SUCCEEDED',
            verification_evidence_id = :verificationEvidenceId,
            evidence_summary = :evidenceSummary,
            completed_at = :completedAt,
            completed_event_id = :completedEventId
        WHERE verification_id = :verificationId AND attempt_status = 'ACTIVE'
        """;

    private static final String MARK_CONFLICT_SQL = """
        UPDATE ticket.ticket_verification_attempts
        SET attempt_status = 'CONFLICT',
            conflict_event_id = :conflictEventId
        WHERE verification_id = :verificationId AND ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketVerificationSuccessPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TicketVerificationAttemptGuard> loadGuard(String verificationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            GUARD_SQL, new MapSqlParameterSource().addValue("verificationId", verificationId)
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID supportQueueId = (UUID) row.get("support_queue_id");
        return Optional.of(new TicketVerificationAttemptGuard(
            TicketId.of((UUID) row.get("ticket_id")),
            TicketDisplayId.of((String) row.get("display_id")),
            TicketStatus.valueOf((String) row.get("ticket_status")),
            ((Number) row.get("ticket_version")).longValue(),
            supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
            (String) row.get("current_support_user_id"),
            (String) row.get("workflow_id"),
            (UUID) row.get("resolution_cycle_id"),
            ((Number) row.get("attempt_number")).intValue(),
            (String) row.get("attempt_status"),
            (UUID) row.get("current_resolution_cycle_id")
        ));
    }

    @Override
    public TicketVerificationSuccessUpdateOutcome applyVerificationSuccess(TicketVerificationSuccessUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketVerificationSuccessUpdateOutcome.Conflict();
        }

        MapSqlParameterSource attemptParams = new MapSqlParameterSource()
            .addValue("verificationId", update.verificationId())
            .addValue("verificationEvidenceId", update.verificationEvidenceId())
            .addValue("evidenceSummary", writeEvidenceSummary(update.evidenceSummary()))
            .addValue("completedAt", Timestamp.from(update.completedAt()))
            .addValue("completedEventId", update.completedEventId());

        int attemptRowsAffected = jdbcTemplate.update(UPDATE_ATTEMPT_SQL, attemptParams);
        if (attemptRowsAffected != 1) {
            return new TicketVerificationSuccessUpdateOutcome.Conflict();
        }

        return new TicketVerificationSuccessUpdateOutcome.Applied(update.expectedVersion() + 1);
    }

    @Override
    public boolean markConflictRequiresReconciliation(TicketId ticketId, String verificationId, String conflictEventId) {
        int rowsAffected = jdbcTemplate.update(MARK_CONFLICT_SQL, new MapSqlParameterSource()
            .addValue("verificationId", verificationId)
            .addValue("ticketId", ticketId.value())
            .addValue("conflictEventId", conflictEventId)
        );
        return rowsAffected == 1;
    }

    private String writeEvidenceSummary(Map<String, Object> evidenceSummary) {
        if (evidenceSummary == null || evidenceSummary.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidenceSummary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize verification evidence summary", e);
        }
    }
}
