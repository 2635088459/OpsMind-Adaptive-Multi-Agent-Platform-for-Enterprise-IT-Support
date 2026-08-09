package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SPEC-TW-030 persistence: the version/state-guarded {@code UPDATE}
 * (mirrors {@code TicketAssignmentPersistenceAdapter}, SPEC-TW-008, same
 * {@code IN (:requiredStatuses)} shape) — status is never written (this
 * command never changes it), only {@code current_team_id}/{@code
 * support_queue_id}/{@code current_support_user_id}.
 */
@Component
public class TicketAssignmentRoutePersistenceAdapter implements TicketAssignmentRouteRepository {

    private static final String UPDATE_SQL = """
        UPDATE ticket.tickets
        SET current_team_id = :newTeamId,
            support_queue_id = :newSupportQueueId,
            current_support_user_id = :newAssigneeId,
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

    public TicketAssignmentRoutePersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketAssignmentRouteUpdateOutcome applyRoute(TicketAssignmentRouteUpdate update) {
        List<String> requiredStatusNames = update.requiredCurrentStatuses().stream().map(Enum::name).collect(Collectors.toList());

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("newTeamId", update.newTeamId())
            .addValue("newSupportQueueId", update.newSupportQueueId().value())
            .addValue("newAssigneeId", update.newAssigneeId())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("requiredStatuses", requiredStatusNames);

        int affected = jdbcTemplate.update(UPDATE_SQL, params);

        if (affected == 1) {
            return new TicketAssignmentRouteUpdateOutcome.Updated(update.expectedVersion() + 1);
        }

        return reclassify(update);
    }

    private TicketAssignmentRouteUpdateOutcome reclassify(TicketAssignmentRouteUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketAssignmentRouteUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketAssignmentRouteUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketAssignmentRouteUpdateOutcome.InvalidState(currentStatus);
    }
}
