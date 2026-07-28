package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §14/§15: walking every page returns each authorized Ticket exactly once, no duplicates or gaps. */
@Tag("integration")
class SupportQueueKeysetPaginationIT extends AbstractSupportQueueIT {

    @Test
    void shouldWalkAllPagesWithoutDuplicatesOrGaps() {
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            seeded.add(seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(i)));
        }

        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        int pageCount = 0;
        do {
            Map<String, String> params = new HashMap<>(Map.of("limit", "10"));
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            ResponseEntity<String> response = queryQueue(
                supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), params
            );
            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);

            JsonNode body = bodyAsJson(response);
            collected.addAll(itemTicketIds(body));
            boolean hasMore = body.get("page").get("hasMore").asBoolean();
            cursor = hasMore ? body.get("page").get("nextCursor").asText() : null;
            pageCount++;
        } while (cursor != null && pageCount < 10);

        assertThat(collected).hasSize(25);
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyInAnyOrderElementsOf(seeded);
    }

    @Test
    void nextCursorShouldOnlyReturnRowsAfterThePreviousKeysetBoundary() {
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        for (int i = 0; i < 3; i++) {
            seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(i));
        }

        ResponseEntity<String> firstPage = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("limit", "2")
        );
        JsonNode firstBody = bodyAsJson(firstPage);
        assertThat(firstBody.get("page").get("hasMore").asBoolean()).isTrue();
        String nextCursor = firstBody.get("page").get("nextCursor").asText();
        List<UUID> firstPageIds = itemTicketIds(firstBody);

        ResponseEntity<String> secondPage = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("limit", "2", "cursor", nextCursor)
        );
        List<UUID> secondPageIds = itemTicketIds(bodyAsJson(secondPage));

        assertThat(secondPageIds).hasSize(1);
        assertThat(secondPageIds).doesNotContainAnyElementsOf(firstPageIds);
    }
}
