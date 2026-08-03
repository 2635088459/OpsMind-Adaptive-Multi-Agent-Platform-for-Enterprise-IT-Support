package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The version/state-guarded {@code UPDATE} (mirrors {@code
 * TicketTriagePersistenceAdapter}, SPEC-TW-007). Uses {@link
 * NamedParameterJdbcTemplate} rather than a positional native query
 * because the required-source-status predicate is a variable-length
 * {@code IN (:requiredStatuses)} list — a single status for Assign/
 * Unassign, three for Reassign ({@code Ticket.REASSIGNABLE_STATUSES}) —
 * which named-parameter collection expansion handles directly.
 */
@Component
public class TicketAssignmentPersistenceAdapter implements TicketAssignmentRepository {

    private static final String UPDATE_SQL = """
        UPDATE ticket.tickets
        SET current_support_user_id = :newAssigneeId,
            assigned_at = :assignedAt,
            assigned_by = :assignedBy,
            status = :newStatus,
            version = version + 1,
            updated_at = :updatedAt
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status IN (:requiredStatuses)
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketAssignmentPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketAssignmentUpdateOutcome applyAssignment(TicketAssignmentUpdate update) {
        List<String> requiredStatusNames = update.requiredCurrentStatuses().stream().map(Enum::name).collect(Collectors.toList());

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("newAssigneeId", update.newAssigneeId())
            .addValue("assignedAt", update.assignedAt() == null ? null : Timestamp.from(update.assignedAt()))
            .addValue("assignedBy", update.assignedBy())
            .addValue("newStatus", update.newStatus().name())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("requiredStatuses", requiredStatusNames);

        int affected = jdbcTemplate.update(UPDATE_SQL, params);

        if (affected == 1) {
            return new TicketAssignmentUpdateOutcome.Updated(update.expectedVersion() + 1);
        }

        return reclassify(update);
    }

    private TicketAssignmentUpdateOutcome reclassify(TicketAssignmentUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketAssignmentUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketAssignmentUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketAssignmentUpdateOutcome.InvalidState(currentStatus);
    }
}
