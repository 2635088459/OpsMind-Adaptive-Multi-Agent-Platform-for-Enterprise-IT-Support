package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTimelineQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineGuard;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Query-side PostgreSQL projection adapter for the Ticket Timeline
 * (SPEC-TW-006 §17). One indexed {@code UNION ALL} query per call, no
 * aggregate rehydration, no lazy JPA graph, no count query, no offset
 * scan.
 */
@Component
public class JdbcTicketTimelineQueryAdapter implements TicketTimelineQueryPort {

    private static final Instant ABSENT_CURSOR_OCCURRED_AT = Instant.EPOCH;
    private static final int ABSENT_CURSOR_ITEM_TYPE_RANK = -1;
    private static final String ABSENT_CURSOR_ITEM_ID = "";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcTicketTimelineQueryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TicketTimelineGuard> loadGuard(TicketId ticketId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ticketId", ticketId.value());
        List<TicketTimelineGuard> rows = jdbcTemplate.query(TicketTimelineQuerySql.LOAD_GUARD, params, this::mapGuardRow);
        return rows.stream().findFirst();
    }

    @Override
    public List<TicketTimelineItem> queryTimeline(
        TicketId ticketId,
        boolean includeInternal,
        Instant snapshotAt,
        TicketTimelineKeysetPosition cursorPosition,
        int limitPlusOne
    ) {
        boolean cursorAbsent = cursorPosition == null;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId.value())
            .addValue("includeInternal", includeInternal)
            .addValue("snapshotAt", Timestamp.from(snapshotAt))
            .addValue("cursorAbsent", cursorAbsent)
            .addValue("lastOccurredAt", Timestamp.from(cursorAbsent ? ABSENT_CURSOR_OCCURRED_AT : cursorPosition.occurredAt()))
            .addValue("lastItemTypeRank", cursorAbsent ? ABSENT_CURSOR_ITEM_TYPE_RANK : cursorPosition.itemTypeRank())
            .addValue("lastItemId", cursorAbsent ? ABSENT_CURSOR_ITEM_ID : cursorPosition.itemId())
            .addValue("limitPlusOne", limitPlusOne);

        return jdbcTemplate.query(TicketTimelineQuerySql.QUERY_TIMELINE, params, this::mapItemRow);
    }

    private TicketTimelineGuard mapGuardRow(ResultSet rs, int rowNum) throws SQLException {
        return new TicketTimelineGuard(
            rs.getString("display_id"),
            rs.getString("requester_id"),
            rs.getString("application_code")
        );
    }

    private TicketTimelineItem mapItemRow(ResultSet rs, int rowNum) throws SQLException {
        return new TicketTimelineItem(
            rs.getString("item_id"),
            TicketTimelineItemType.valueOf(rs.getString("item_type")),
            rs.getString("visibility"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("actor_type"),
            rs.getString("actor_id"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("transition_id"),
            rs.getString("reason_code"),
            rs.getString("message_type"),
            rs.getString("content"),
            rs.getLong("related_version")
        );
    }
}
