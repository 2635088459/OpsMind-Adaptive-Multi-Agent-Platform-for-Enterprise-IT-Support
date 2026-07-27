package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

/**
 * SQL text for List Requester Tickets (SPEC-TW-003 §13), kept apart from
 * {@link JdbcRequesterTicketQueryAdapter} so the query shape is reviewable
 * on its own. {@code IN (:list)} is used instead of the spec's {@code =
 * ANY(:array)} shape — functionally equivalent, and Spring's
 * NamedParameterJdbcTemplate expands a {@code List} parameter into an
 * {@code IN} clause natively without requiring a JDBC {@code Array}
 * built from a live {@code Connection}. Empty status/applicationCode
 * filters pass a single non-matching placeholder value together with a
 * {@code TRUE} "empty" flag, so the {@code IN} clause is always
 * syntactically non-empty while the flag's {@code OR} makes the clause a
 * no-op — this keeps ownership and every filter in one SQL predicate
 * instead of loading rows and filtering them in Java.
 *
 * <p>{@code :createdFrom}/{@code :createdTo} are cast explicitly
 * ({@code ::timestamptz}) because a bare {@code ? IS NULL} gives
 * PostgreSQL's parser no syntactic type to infer for the placeholder —
 * it fails at parse time with "could not determine data type of
 * parameter $n" regardless of the JDBC-side type hint, since the failure
 * happens before any bound value reaches the server.
 */
final class RequesterTicketQuerySql {

    static final String LIST_FOR_REQUESTER = """
        SELECT ticket_id, display_id, title, application_code, status, priority, created_at, updated_at, version
        FROM ticket.tickets
        WHERE requester_id = :requesterId
          AND (:statusesEmpty = TRUE OR status IN (:statuses))
          AND (:applicationsEmpty = TRUE OR application_code IN (:applicationCodes))
          AND (:createdFrom::timestamptz IS NULL OR created_at >= :createdFrom::timestamptz)
          AND (:createdTo::timestamptz IS NULL OR created_at < :createdTo::timestamptz)
          AND (
            :cursorAbsent = TRUE
            OR created_at < :lastCreatedAt
            OR (created_at = :lastCreatedAt AND ticket_id < :lastTicketId)
          )
        ORDER BY created_at DESC, ticket_id DESC
        LIMIT :limitPlusOne
        """;

    private RequesterTicketQuerySql() {
    }
}
