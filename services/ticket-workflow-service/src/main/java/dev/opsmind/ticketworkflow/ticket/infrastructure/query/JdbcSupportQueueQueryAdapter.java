package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Query-side PostgreSQL projection adapter for the Support Queue
 * (SPEC-TW-005 §19). One indexed query per call, no aggregate rehydration,
 * no count query, no offset scan; every filter and the authorization
 * scope are part of the SQL predicate in {@link SupportQueueQuerySql}.
 */
@Component
public class JdbcSupportQueueQueryAdapter implements SupportQueueQueryPort {

    private static final String NONE_PLACEHOLDER = "__NONE__";
    private static final Instant ABSENT_CURSOR_CREATED_AT = Instant.EPOCH;
    private static final UUID ABSENT_CURSOR_TICKET_ID = new UUID(0L, 0L);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcSupportQueueQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SupportTicketSummary> queryQueue(
        SupportQueueScope scope,
        SupportQueueFilters filters,
        Instant evaluationTime,
        Duration atRiskWindow,
        SupportQueueKeysetPosition cursorPosition,
        int limitPlusOne
    ) {
        boolean statusesEmpty = filters.statuses().isEmpty();
        boolean priorityRanksEmpty = filters.priorities().isEmpty();
        boolean slaStatesEmpty = filters.slaStates().isEmpty();
        boolean cursorAbsent = cursorPosition == null;

        // §7: a requested applicationCode/assignedTeam filter is already validated
        // as a subset of scope before this adapter is called; when the request
        // omits the dimension, the actor's complete approved scope applies.
        List<String> effectiveApplicationCodes = filters.applicationCodes().isEmpty()
            ? names(scope.allowedApplicationCodes().stream().map(Enum::name))
            : names(filters.applicationCodes().stream().map(Enum::name));
        List<String> effectiveTeamIds = filters.assignedTeams().isEmpty()
            ? List.copyOf(scope.allowedTeamIds())
            : List.copyOf(filters.assignedTeams());

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("evaluationTime", Timestamp.from(evaluationTime))
            .addValue("atRiskWindowSeconds", atRiskWindow.toSeconds())
            .addValue("statusesEmpty", statusesEmpty)
            .addValue("statuses", statusesEmpty ? List.of(NONE_PLACEHOLDER) : names(filters.statuses().stream().map(Enum::name)))
            .addValue("applicationCodes", effectiveApplicationCodes.isEmpty() ? List.of(NONE_PLACEHOLDER) : effectiveApplicationCodes)
            .addValue("teamIds", effectiveTeamIds.isEmpty() ? List.of(NONE_PLACEHOLDER) : effectiveTeamIds)
            .addValue("unassignedOnly", filters.unassignedOnly())
            .addValue("assignedAgent", filters.assignedAgent())
            .addValue("createdFrom", filters.createdFrom() == null ? null : Timestamp.from(filters.createdFrom()), Types.TIMESTAMP)
            .addValue("createdTo", filters.createdTo() == null ? null : Timestamp.from(filters.createdTo()), Types.TIMESTAMP)
            .addValue("priorityRanksEmpty", priorityRanksEmpty)
            .addValue("priorityRanks", priorityRanksEmpty ? List.of(-1) : filters.priorities().stream().map(SupportQueuePriority::priorityRank).toList())
            .addValue("slaStatesEmpty", slaStatesEmpty)
            .addValue("slaStates", slaStatesEmpty ? List.of(NONE_PLACEHOLDER) : names(filters.slaStates().stream().map(Enum::name)))
            .addValue("cursorAbsent", cursorAbsent)
            .addValue("lastSlaRank", cursorAbsent ? -1 : cursorPosition.slaRank())
            .addValue("lastPriorityRank", cursorAbsent ? -1 : cursorPosition.priorityRank())
            .addValue("lastCreatedAt", Timestamp.from(cursorAbsent ? ABSENT_CURSOR_CREATED_AT : cursorPosition.createdAt()))
            .addValue("lastTicketId", cursorAbsent ? ABSENT_CURSOR_TICKET_ID : cursorPosition.ticketId())
            .addValue("limitPlusOne", limitPlusOne);

        return jdbcTemplate.query(SupportQueueQuerySql.QUERY_QUEUE, params, this::mapRow);
    }

    private static List<String> names(java.util.stream.Stream<String> values) {
        return values.toList();
    }

    private SupportTicketSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        String teamId = rs.getString("current_team_id");
        String agentId = rs.getString("current_support_user_id");
        var priority = SupportQueuePriority.fromTicketPriority(TicketPriority.valueOf(rs.getString("priority")));
        return new SupportTicketSummary(
            rs.getObject("ticket_id", UUID.class),
            rs.getString("display_id"),
            rs.getString("title"),
            rs.getString("application_code"),
            rs.getString("status"),
            priority,
            rs.getString("requester_id"),
            teamId,
            agentId,
            agentId == null,
            SlaQueueState.valueOf(rs.getString("sla_state")),
            toInstant(rs.getTimestamp("response_due_at")),
            toInstant(rs.getTimestamp("resolution_due_at")),
            rs.getInt("sla_rank"),
            rs.getInt("priority_rank"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version")
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
