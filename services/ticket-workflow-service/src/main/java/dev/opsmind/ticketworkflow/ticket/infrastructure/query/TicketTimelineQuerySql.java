package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

/**
 * SQL text for the Ticket Timeline (SPEC-TW-006 §18, §16), kept apart from
 * {@link JdbcTicketTimelineQueryAdapter} so the query shape is reviewable
 * on its own.
 *
 * <p>{@code UNION ALL} merges three sources into one chronological stream:
 * the Ticket's own creation (a synthetic single row, not a stored event),
 * {@code ticket.ticket_status_history}, and {@code ticket.ticket_messages}.
 * Every {@code NULL} placeholder column is explicitly cast so PostgreSQL
 * never has to infer a common type across three UNION branches on its own
 * — the same defensive pattern already required for nullable filter
 * parameters elsewhere in this codebase, applied here to UNION column
 * typing instead.
 *
 * <p>{@code :includeInternal} gates {@code INTERNAL_SUPPORT_NOTE} rows in
 * SQL, not in Java after loading (§17): an Employee or Support-public
 * query never reads an internal row at all.
 *
 * <p>The keyset predicate is one row-value comparison over {@code
 * (occurred_at, item_type_rank, item_id)}, mirroring the Support Queue
 * precedent (SPEC-TW-005), plus the fixed {@code snapshotAt} upper bound
 * that keeps one cursor session's view stable (§13).
 */
final class TicketTimelineQuerySql {

    static final String LOAD_GUARD = """
        SELECT display_id, requester_id, application_code
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    static final String QUERY_TIMELINE = """
        WITH timeline AS (
            SELECT
                'TICKET_CREATED:' || t.ticket_id::text AS item_id,
                'TICKET_CREATED'::text AS item_type,
                0 AS item_type_rank,
                'PUBLIC'::text AS visibility,
                t.created_at AS occurred_at,
                t.created_by_type::text AS actor_type,
                t.created_by_id::text AS actor_id,
                NULL::varchar(32) AS from_status,
                NULL::varchar(32) AS to_status,
                NULL::varchar(16) AS transition_id,
                NULL::varchar(64) AS reason_code,
                NULL::varchar(32) AS message_type,
                NULL::text AS content,
                0::bigint AS related_version
            FROM ticket.tickets t
            WHERE t.ticket_id = :ticketId

            UNION ALL

            SELECT
                'STATUS_HISTORY:' || h.history_id::text AS item_id,
                'STATUS_CHANGED'::text AS item_type,
                1 AS item_type_rank,
                'PUBLIC'::text AS visibility,
                h.occurred_at AS occurred_at,
                h.actor_type::text AS actor_type,
                h.actor_id::text AS actor_id,
                h.from_status AS from_status,
                h.to_status AS to_status,
                h.transition_id AS transition_id,
                h.reason_code AS reason_code,
                NULL::varchar(32) AS message_type,
                NULL::text AS content,
                h.aggregate_version AS related_version
            FROM ticket.ticket_status_history h
            WHERE h.ticket_id = :ticketId

            UNION ALL

            SELECT
                'MESSAGE:' || m.message_id::text AS item_id,
                m.message_type::text AS item_type,
                CASE m.message_type
                    WHEN 'PUBLIC_REQUESTER_MESSAGE' THEN 2
                    WHEN 'PUBLIC_SUPPORT_MESSAGE' THEN 3
                    WHEN 'INTERNAL_SUPPORT_NOTE' THEN 4
                END AS item_type_rank,
                m.visibility::text AS visibility,
                m.created_at AS occurred_at,
                m.author_type::text AS actor_type,
                m.author_id::text AS actor_id,
                NULL::varchar(32) AS from_status,
                NULL::varchar(32) AS to_status,
                NULL::varchar(16) AS transition_id,
                NULL::varchar(64) AS reason_code,
                m.message_type AS message_type,
                m.content AS content,
                m.version AS related_version
            FROM ticket.ticket_messages m
            WHERE m.ticket_id = :ticketId
              AND (:includeInternal = TRUE OR m.visibility = 'PUBLIC')
        )
        SELECT *
        FROM timeline
        WHERE occurred_at <= :snapshotAt::timestamptz
          AND (
            :cursorAbsent = TRUE
            OR (occurred_at, item_type_rank, item_id) > (:lastOccurredAt::timestamptz, :lastItemTypeRank::integer, :lastItemId::text)
          )
        ORDER BY occurred_at ASC, item_type_rank ASC, item_id ASC
        LIMIT :limitPlusOne
        """;

    private TicketTimelineQuerySql() {
    }
}
