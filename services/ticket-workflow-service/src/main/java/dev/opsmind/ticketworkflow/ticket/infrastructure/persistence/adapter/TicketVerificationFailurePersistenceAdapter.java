package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdateOutcome;
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
 * SPEC-TW-024 persistence: the guard read mirrors {@code
 * TicketVerificationSuccessPersistenceAdapter}'s (SPEC-TW-023) exactly,
 * kept as its own class/bean per this codebase's one-adapter-per-spec
 * convention. The write path's target status ({@code IN_PROGRESS}/{@code
 * ESCALATED}/{@code FAILED}) is supplied by the caller, already computed by
 * {@code Ticket.applyVerificationFailure}, rather than hardcoded here —
 * mirrors {@code TicketToolExecutionFailedPersistenceAdapter}'s (SPEC-TW-020)
 * identical shape.
 */
@Component
public class TicketVerificationFailurePersistenceAdapter implements TicketVerificationFailureGuardPort, TicketVerificationFailureRepository {

    private static final String GUARD_SQL = """
        SELECT va.ticket_id, va.workflow_id, va.resolution_cycle_id, va.attempt_number, va.attempt_status,
               t.display_id, t.status AS ticket_status, t.version AS ticket_version, t.support_queue_id,
               t.current_support_user_id, t.current_resolution_cycle_id
        FROM ticket.ticket_verification_attempts va
        JOIN ticket.tickets t ON t.ticket_id = va.ticket_id
        WHERE va.verification_id = :verificationId
        """;

    private static final String COUNT_FAILED_SQL = """
        SELECT count(*) FROM ticket.ticket_verification_attempts
        WHERE ticket_id = :ticketId AND resolution_cycle_id = :resolutionCycleId AND attempt_status = 'FAILED'
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = :newStatus,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'VERIFYING'
        """;

    private static final String UPDATE_ATTEMPT_SQL = """
        UPDATE ticket.ticket_verification_attempts
        SET attempt_status = 'FAILED',
            failure_code = :failureCode,
            failure_class = :failureClass,
            unsafe_result = :unsafeResult,
            failed_at = :failedAt,
            failed_event_id = :failedEventId
        WHERE verification_id = :verificationId AND attempt_status = 'ACTIVE'
        """;

    private static final String MARK_CONFLICT_SQL = """
        UPDATE ticket.ticket_verification_attempts
        SET attempt_status = 'CONFLICT',
            conflict_event_id = :conflictEventId
        WHERE verification_id = :verificationId AND ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketVerificationFailurePersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    public int countFailedAttempts(TicketId ticketId, UUID resolutionCycleId) {
        Integer count = jdbcTemplate.queryForObject(
            COUNT_FAILED_SQL,
            new MapSqlParameterSource().addValue("ticketId", ticketId.value()).addValue("resolutionCycleId", resolutionCycleId),
            Integer.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public TicketVerificationFailureUpdateOutcome applyVerificationFailure(TicketVerificationFailureUpdate update) {
        MapSqlParameterSource ticketParams = new MapSqlParameterSource()
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("newStatus", update.newStatus().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()));

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, ticketParams);
        if (ticketRowsAffected != 1) {
            return new TicketVerificationFailureUpdateOutcome.Conflict();
        }

        MapSqlParameterSource attemptParams = new MapSqlParameterSource()
            .addValue("verificationId", update.verificationId())
            .addValue("failureCode", update.failureCode())
            .addValue("failureClass", update.failureClass())
            .addValue("unsafeResult", update.unsafeResult())
            .addValue("failedAt", Timestamp.from(update.failedAt()))
            .addValue("failedEventId", update.failedEventId());

        int attemptRowsAffected = jdbcTemplate.update(UPDATE_ATTEMPT_SQL, attemptParams);
        if (attemptRowsAffected != 1) {
            return new TicketVerificationFailureUpdateOutcome.Conflict();
        }

        return new TicketVerificationFailureUpdateOutcome.Applied(update.expectedVersion() + 1);
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
}
