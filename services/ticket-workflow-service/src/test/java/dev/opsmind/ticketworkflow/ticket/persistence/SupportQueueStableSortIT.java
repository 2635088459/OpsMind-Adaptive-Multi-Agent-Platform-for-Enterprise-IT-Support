package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §13: default ordering is slaRank ASC, priorityRank ASC,
 * createdAt ASC, ticketId ASC — ticketId resolves ties when every other
 * key is equal.
 */
@Tag("integration")
class SupportQueueStableSortIT extends AbstractSupportQueueIT {

    @Test
    void shouldOrderByTicketIdWhenSlaRankPriorityRankAndCreatedAtAreEqual() {
        Instant sameCreatedAt = Instant.now().minusSeconds(600);
        List<UUID> ticketIds = java.util.stream.Stream.generate(UUID::randomUUID).limit(5).toList();

        for (UUID ticketId : ticketIds) {
            seedTicket(
                ticketId, DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, null,
                sameCreatedAt, "ACTIVE", sameCreatedAt.plusSeconds(86400)
            );
        }

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        // PostgreSQL's UUID ordering is a byte-wise unsigned comparison, which does not always agree
        // with java.util.UUID#compareTo's signed-long comparison — so the expected order is read back
        // from PostgreSQL itself rather than recomputed with Java's Comparable<UUID>. Inlining these
        // test-generated UUIDs as literals is safe (no external input reaches this string).
        String idList = ticketIds.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));
        List<UUID> postgresOrder = jdbcTemplate.queryForList(
            "SELECT ticket_id FROM ticket.tickets WHERE ticket_id IN (" + idList + ") ORDER BY ticket_id ASC", UUID.class
        );
        assertThat(itemTicketIds(bodyAsJson(response))).isEqualTo(postgresOrder);
    }

    @Test
    void shouldOrderBySlaRankThenPriorityRankThenCreatedAt() {
        // The query's evaluationTime is real Instant.now() (SystemClockAdapter), not a fixed literal.
        // "breached" is anchored to a created_at safely before its resolution_due_at (satisfying
        // ck_sla_time_order) while resolution_due_at itself stays before real "now" (genuinely
        // BREACHED). "olderActive" is older (an earlier createdAt) but has a resolution_due_at safely
        // in the future, so it stays ACTIVE despite being the older Ticket.
        Instant now = Instant.now();
        UUID breached = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "LOW", DEFAULT_TEAM, null,
            now.minusSeconds(7200), "ACTIVE", now.minusSeconds(3600)
        );
        UUID olderActive = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "CRITICAL", DEFAULT_TEAM, null,
            now.minusSeconds(9000), "ACTIVE", now.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(breached, olderActive);
    }
}
