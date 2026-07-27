package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

import dev.opsmind.ticketworkflow.ticket.application.port.out.RequesterTicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Query-side PostgreSQL projection adapter for List Requester Tickets
 * (SPEC-TW-003 §12). One indexed query per call, no aggregate rehydration,
 * no count query, no offset scan.
 */
@Component
public class JdbcRequesterTicketQueryAdapter implements RequesterTicketQueryPort {

    private static final String NONE_PLACEHOLDER = "__NONE__";
    private static final Instant ABSENT_CURSOR_CREATED_AT = Instant.EPOCH;
    private static final UUID ABSENT_CURSOR_TICKET_ID = new UUID(0L, 0L);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcRequesterTicketQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RequesterTicketSummary> listForRequester(
        String requesterId,
        TicketListFilters filters,
        Instant cursorLastCreatedAt,
        UUID cursorLastTicketId,
        int limitPlusOne
    ) {
        boolean statusesEmpty = filters.statuses().isEmpty();
        boolean applicationsEmpty = filters.applicationCodes().isEmpty();
        boolean cursorAbsent = cursorLastCreatedAt == null || cursorLastTicketId == null;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("requesterId", requesterId)
            .addValue("statusesEmpty", statusesEmpty)
            .addValue("statuses", statusesEmpty ? List.of(NONE_PLACEHOLDER) : names(filters.statuses().stream().map(Enum::name)))
            .addValue("applicationsEmpty", applicationsEmpty)
            .addValue("applicationCodes", applicationsEmpty ? List.of(NONE_PLACEHOLDER) : names(filters.applicationCodes().stream().map(Enum::name)))
            .addValue("createdFrom", filters.createdFrom() == null ? null : Timestamp.from(filters.createdFrom()), Types.TIMESTAMP)
            .addValue("createdTo", filters.createdTo() == null ? null : Timestamp.from(filters.createdTo()), Types.TIMESTAMP)
            .addValue("cursorAbsent", cursorAbsent)
            .addValue("lastCreatedAt", Timestamp.from(cursorAbsent ? ABSENT_CURSOR_CREATED_AT : cursorLastCreatedAt))
            .addValue("lastTicketId", cursorAbsent ? ABSENT_CURSOR_TICKET_ID : cursorLastTicketId)
            .addValue("limitPlusOne", limitPlusOne);

        return jdbcTemplate.query(RequesterTicketQuerySql.LIST_FOR_REQUESTER, params, this::mapRow);
    }

    private static List<String> names(java.util.stream.Stream<String> values) {
        return values.toList();
    }

    private RequesterTicketSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RequesterTicketSummary(
            rs.getObject("ticket_id", UUID.class),
            rs.getString("display_id"),
            rs.getString("title"),
            rs.getString("application_code"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version")
        );
    }
}
