package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §8/§9: keyset cursor pagination walks every row exactly once. */
@Tag("integration")
class ListRequesterTicketsCursorIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldWalkAllPagesWithoutDuplicatesOrOmissions() {
        Instant base = Instant.parse("2026-07-23T16:30:00Z");
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seeded.add(seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.minusSeconds(i * 60L)));
        }

        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, String> params = new HashMap<>(Map.of("limit", "2"));
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), params);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            JsonNode body = bodyAsJson(response);
            collected.addAll(itemTicketIds(body));
            boolean hasMore = body.get("page").get("hasMore").asBoolean();
            JsonNode nextCursorNode = body.get("page").get("nextCursor");
            cursor = (nextCursorNode.isNull()) ? null : nextCursorNode.asText();

            assertThat(hasMore).isEqualTo(cursor != null);
            pages++;
            assertThat(pages).isLessThanOrEqualTo(10);
        } while (cursor != null);

        assertThat(collected).containsExactlyElementsOf(seeded);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void nextPageShouldNotRepeatFirstPageItems() {
        Instant base = Instant.parse("2026-07-23T16:30:00Z");
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base);
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.minusSeconds(60));
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.minusSeconds(120));

        ResponseEntity<String> firstPage = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of("limit", "2"));
        JsonNode firstBody = bodyAsJson(firstPage);
        List<UUID> firstItems = itemTicketIds(firstBody);
        String cursor = firstBody.get("page").get("nextCursor").asText();

        ResponseEntity<String> secondPage = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of("limit", "2", "cursor", cursor));
        List<UUID> secondItems = itemTicketIds(bodyAsJson(secondPage));

        assertThat(secondItems).doesNotContainAnyElementsOf(firstItems);
    }
}
