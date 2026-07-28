package dev.opsmind.ticketworkflow.ticket.infrastructure.query;

/**
 * SQL text for the Support Queue (SPEC-TW-005 §13, §15, §19), kept apart
 * from {@link JdbcSupportQueueQueryAdapter} so the query shape is
 * reviewable on its own.
 *
 * <p>{@code slaRank}/{@code slaState} are computed, not stored: {@code
 * ticket.ticket_sla_cycles} only records {@code ACTIVE/PAUSED/MET/
 * BREACHED/CANCELLED}, so {@code AT_RISK} and a live (not-yet-swept)
 * {@code BREACHED} are derived from {@code resolution_due_at} against the
 * cursor session's fixed {@code :evaluationTime} (§11) plus a configured
 * {@code atRiskWindow}. The join key is {@code resolution_cycle_id =
 * current_resolution_cycle_id} — a 1:1 relationship (see {@code
 * uq_sla_resolution_cycle}) — mirroring Get Ticket's Support view exactly
 * (SPEC-TW-002), so there is never more than one SLA cycle row per Ticket.
 *
 * <p>Authorization and every filter are pushed into the outer {@code
 * WHERE} clause, not applied after loading rows. Application code and
 * team are each backed by an "empty means match-nothing placeholder"
 * flag, exactly like {@code RequesterTicketQuerySql}, so an actor with no
 * authorized application scope sees an empty Queue rather than an
 * accidental full scan. {@code current_team_id IS NULL} is always allowed
 * through the team predicate: an untriaged Ticket has not yet been routed
 * to any team's exclusive ownership, so it stays visible to any actor
 * already authorized for the Ticket's application.
 *
 * <p>The keyset predicate uses one row-value comparison — {@code (sla_rank,
 * priority_rank, created_at, ticket_id) > (...)} — implementing the
 * lexicographic advance across all four sort columns in a single
 * expression (§15), rather than the expanded OR-chain used by the
 * two-column List Requester Tickets predicate.
 */
final class SupportQueueQuerySql {

    static final String QUERY_QUEUE = """
        WITH queue AS (
            SELECT
                t.ticket_id, t.display_id, t.title, t.application_code, t.status, t.priority,
                t.requester_id, t.current_team_id, t.current_support_user_id,
                t.created_at, t.updated_at, t.version,
                sc.response_due_at, sc.resolution_due_at,
                CASE t.priority
                    WHEN 'CRITICAL' THEN 0
                    WHEN 'HIGH' THEN 1
                    WHEN 'MEDIUM' THEN 2
                    WHEN 'LOW' THEN 3
                    ELSE 4
                END AS priority_rank,
                CASE
                    WHEN sc.status = 'BREACHED' THEN 'BREACHED'
                    WHEN sc.status = 'ACTIVE' AND sc.resolution_due_at IS NOT NULL
                        AND sc.resolution_due_at <= :evaluationTime::timestamptz THEN 'BREACHED'
                    WHEN sc.status = 'ACTIVE' AND sc.resolution_due_at IS NOT NULL
                        AND sc.resolution_due_at <= :evaluationTime::timestamptz + (:atRiskWindowSeconds || ' seconds')::interval THEN 'AT_RISK'
                    WHEN sc.status = 'ACTIVE' THEN 'ACTIVE'
                    WHEN sc.status = 'PAUSED' THEN 'PAUSED'
                    WHEN sc.status IN ('MET', 'CANCELLED') THEN 'COMPLETED'
                    ELSE 'ACTIVE'
                END AS sla_state,
                CASE
                    WHEN sc.status = 'BREACHED' THEN 0
                    WHEN sc.status = 'ACTIVE' AND sc.resolution_due_at IS NOT NULL
                        AND sc.resolution_due_at <= :evaluationTime::timestamptz THEN 0
                    WHEN sc.status = 'ACTIVE' AND sc.resolution_due_at IS NOT NULL
                        AND sc.resolution_due_at <= :evaluationTime::timestamptz + (:atRiskWindowSeconds || ' seconds')::interval THEN 1
                    WHEN sc.status = 'ACTIVE' THEN 2
                    WHEN sc.status = 'PAUSED' THEN 3
                    WHEN sc.status IN ('MET', 'CANCELLED') THEN 4
                    ELSE 2
                END AS sla_rank
            FROM ticket.tickets t
            LEFT JOIN ticket.ticket_sla_cycles sc ON sc.resolution_cycle_id = t.current_resolution_cycle_id
            WHERE t.status NOT IN ('CLOSED', 'CANCELLED')
              AND (:statusesEmpty = TRUE OR t.status IN (:statuses))
              AND t.application_code IN (:applicationCodes)
              AND (t.current_team_id IS NULL OR t.current_team_id IN (:teamIds))
              AND (:unassignedOnly = FALSE OR t.current_support_user_id IS NULL)
              AND (:assignedAgent::text IS NULL OR t.current_support_user_id = :assignedAgent)
              AND (:createdFrom::timestamptz IS NULL OR t.created_at >= :createdFrom::timestamptz)
              AND (:createdTo::timestamptz IS NULL OR t.created_at < :createdTo::timestamptz)
        )
        SELECT *
        FROM queue
        WHERE (:priorityRanksEmpty = TRUE OR priority_rank IN (:priorityRanks))
          AND (:slaStatesEmpty = TRUE OR sla_state IN (:slaStates))
          AND (
            :cursorAbsent = TRUE
            OR (sla_rank, priority_rank, created_at, ticket_id) > (:lastSlaRank, :lastPriorityRank, :lastCreatedAt::timestamptz, :lastTicketId::uuid)
          )
        ORDER BY sla_rank ASC, priority_rank ASC, created_at ASC, ticket_id ASC
        LIMIT :limitPlusOne
        """;

    private SupportQueueQuerySql() {
    }
}
