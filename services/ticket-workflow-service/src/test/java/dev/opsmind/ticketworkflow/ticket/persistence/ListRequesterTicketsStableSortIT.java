package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 §7/§9: equal createdAt values are resolved by ticketId DESC,
 * and pagination stays gap/duplicate-free.
 *
 * <p>The expected order is read directly from PostgreSQL ({@code ORDER BY
 * ticket_id DESC}), not recomputed with {@code UUID.compareTo} — Java's
 * {@code UUID} orders by signed-long comparison of the two 8-byte halves,
 * while PostgreSQL's {@code uuid} type orders byte-wise unsigned; the two
 * can disagree, so only the database's own ordering is a valid oracle for
 * what "ticketId DESC" means here.
 */
@Tag("integration")
class ListRequesterTicketsStableSortIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldOrderTiedCreatedAtByTicketIdDescending() {
        Instant sameInstant = Instant.parse("2026-07-23T16:30:00Z");
        for (int i = 0; i < 4; i++) {
            seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", sameInstant);
        }
        List<UUID> expectedOrder = ticketIdsInDatabaseOrder();

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of("limit", "10"));

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    void shouldPaginateTiedCreatedAtValuesWithoutDuplicates() {
        Instant sameInstant = Instant.parse("2026-07-23T16:30:00Z");
        for (int i = 0; i < 5; i++) {
            seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", sameInstant);
        }
        List<UUID> expectedOrder = ticketIdsInDatabaseOrder();

        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, String> params = new HashMap<>(Map.of("limit", "1"));
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            var response = listTickets(employeeToken(DEFAULT_REQUESTER), params);
            var body = bodyAsJson(response);
            collected.addAll(itemTicketIds(body));
            var nextCursorNode = body.get("page").get("nextCursor");
            cursor = nextCursorNode.isNull() ? null : nextCursorNode.asText();
        } while (cursor != null);

        assertThat(collected).containsExactlyElementsOf(expectedOrder);
    }

    private List<UUID> ticketIdsInDatabaseOrder() {
        return jdbcTemplate.queryForList(
            "SELECT ticket_id FROM ticket.tickets WHERE requester_id = ? ORDER BY created_at DESC, ticket_id DESC",
            UUID.class, DEFAULT_REQUESTER
        );
    }
}
