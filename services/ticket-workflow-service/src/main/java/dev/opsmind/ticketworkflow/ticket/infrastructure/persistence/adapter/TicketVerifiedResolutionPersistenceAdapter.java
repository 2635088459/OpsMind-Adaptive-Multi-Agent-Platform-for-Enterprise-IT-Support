package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedVerificationEvidence;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-025 persistence: the guard read joins the ticket with its current
 * resolution cycle (mirrors {@code TicketResolveGuardAdapter}, SPEC-TW-010);
 * the evidence lookup is a separate, narrowly-scoped query against {@code
 * ticket_verification_attempts} (SPEC-TW-022/023) — {@code SUCCEEDED} AND
 * bound to this exact ticket AND this exact (current) resolution cycle, so a
 * stale/old-cycle or wrong-ticket row can never satisfy it. The write path
 * updates the ticket row and completes the resolution cycle as two
 * statements against the same transaction, mirroring {@code
 * TicketResolvePersistenceAdapter}'s write shape exactly, plus the new
 * verification-evidence columns (V031).
 */
@Component
public class TicketVerifiedResolutionPersistenceAdapter implements VerifiedResolutionGuardPort, VerifiedResolutionRepository {

    private static final String GUARD_SQL = """
        SELECT t.display_id, t.status, t.version, t.support_queue_id, t.current_support_user_id,
               t.current_resolution_cycle_id, c.cycle_status
        FROM ticket.tickets t
        LEFT JOIN ticket.ticket_resolution_cycles c ON c.resolution_cycle_id = t.current_resolution_cycle_id
        WHERE t.ticket_id = ?
        """;

    private static final String EVIDENCE_SQL = """
        SELECT verification_id, workflow_id, attempt_number
        FROM ticket.ticket_verification_attempts
        WHERE ticket_id = :ticketId
          AND resolution_cycle_id = :resolutionCycleId
          AND verification_evidence_id = :verificationEvidenceId
          AND attempt_status = 'SUCCEEDED'
        ORDER BY completed_at DESC
        LIMIT 1
        """;

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'RESOLVED',
            resolved_at = :resolvedAt,
            resolved_by = :resolvedById,
            resolution_code = :resolutionCode,
            resolution_summary = :resolutionSummary,
            verification_evidence_id = :verificationEvidenceId,
            waiting_for_requester_since = NULL,
            approval_reference = NULL,
            auto_close_due_at = :autoCloseDueAt,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'VERIFYING'
          AND current_support_user_id IS NOT NULL
        """;

    private static final String UPDATE_CYCLE_SQL = """
        UPDATE ticket.ticket_resolution_cycles
        SET cycle_status = 'RESOLVED',
            resolved_at = :resolvedAt,
            resolution_code = :resolutionCode,
            resolution_summary = :resolutionSummary,
            resolved_by_type = :resolvedByType,
            resolved_by_id = :resolvedById,
            verification_id = :verificationId,
            verification_evidence_id = :verificationEvidenceId
        WHERE resolution_cycle_id = :resolutionCycleId
          AND ticket_id = :ticketId
          AND cycle_status = 'ACTIVE'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public TicketVerifiedResolutionPersistenceAdapter(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public Optional<VerifiedResolutionGuard> loadGuard(TicketId ticketId) {
        List<VerifiedResolutionGuard> rows = jdbcTemplate.query(
            GUARD_SQL,
            (rs, rowNum) -> {
                UUID supportQueueId = (UUID) rs.getObject("support_queue_id");
                UUID currentResolutionCycleId = (UUID) rs.getObject("current_resolution_cycle_id");
                String cycleStatus = rs.getString("cycle_status");
                return new VerifiedResolutionGuard(
                    ticketId,
                    TicketDisplayId.of(rs.getString("display_id")),
                    TicketStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    supportQueueId == null ? null : SupportQueueId.of(supportQueueId),
                    rs.getString("current_support_user_id"),
                    currentResolutionCycleId,
                    cycleStatus == null ? null : ResolutionCycleStatus.valueOf(cycleStatus)
                );
            },
            ticketId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<VerifiedVerificationEvidence> findCurrentSucceededEvidence(TicketId ticketId, UUID resolutionCycleId, String verificationEvidenceId) {
        List<VerifiedVerificationEvidence> rows = namedParameterJdbcTemplate.query(
            EVIDENCE_SQL,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId.value())
                .addValue("resolutionCycleId", resolutionCycleId)
                .addValue("verificationEvidenceId", verificationEvidenceId),
            (rs, rowNum) -> new VerifiedVerificationEvidence(
                rs.getString("verification_id"), rs.getString("workflow_id"), rs.getInt("attempt_number")
            )
        );
        return rows.stream().findFirst();
    }

    @Override
    public VerifiedResolutionUpdateOutcome applyResolution(VerifiedResolutionUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("resolvedAt", Timestamp.from(update.resolvedAt()))
            .addValue("resolvedById", update.resolvedById())
            .addValue("resolvedByType", update.resolvedByType())
            .addValue("resolutionCode", update.resolutionCode().name())
            .addValue("resolutionSummary", update.resolutionSummary())
            .addValue("verificationId", update.verificationId())
            .addValue("verificationEvidenceId", update.verificationEvidenceId())
            .addValue("autoCloseDueAt", Timestamp.from(update.autoCloseDueAt()))
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("resolutionCycleId", update.resolutionCycleId());

        int ticketRowsAffected = namedParameterJdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int cycleRowsAffected = namedParameterJdbcTemplate.update(UPDATE_CYCLE_SQL, params);
        if (cycleRowsAffected != 1) {
            return new VerifiedResolutionUpdateOutcome.ResolutionCycleConflict();
        }

        return new VerifiedResolutionUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private VerifiedResolutionUpdateOutcome reclassify(VerifiedResolutionUpdate update) {
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new VerifiedResolutionUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new VerifiedResolutionUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new VerifiedResolutionUpdateOutcome.NotAssigned();
        }
        return new VerifiedResolutionUpdateOutcome.InvalidState(currentStatus);
    }
}
