package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 §9 / acceptance "New Ticket insertion between pages causes no
 * duplicate": the cursor's sort keys (createdAt, ticketId) are immutable,
 * so a Ticket created after the first page was read never appears in that
 * same cursor's later pages, and no already-returned Ticket repeats.
 */
@Tag("integration")
class ListRequesterTicketsConcurrentInsertIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldNotLeakOrDuplicateWhenANewerTicketIsInsertedBetweenPages() {
        Instant base = Instant.parse("2026-07-23T16:30:00Z");
        UUID first = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base);
        UUID second = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.minusSeconds(60));
        UUID third = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.minusSeconds(120));

        ResponseEntity<String> firstPageResponse = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of("limit", "2"));
        JsonNode firstPage = bodyAsJson(firstPageResponse);
        List<UUID> firstPageItems = itemTicketIds(firstPage);
        String cursor = firstPage.get("page").get("nextCursor").asText();
        assertThat(firstPageItems).containsExactly(first, second);

        UUID newerTicket = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", base.plusSeconds(3600));

        ResponseEntity<String> secondPageResponse = listTickets(
            employeeToken(DEFAULT_REQUESTER), Map.of("limit", "2", "cursor", cursor)
        );
        List<UUID> secondPageItems = itemTicketIds(bodyAsJson(secondPageResponse));

        assertThat(secondPageItems).containsExactly(third);
        assertThat(secondPageItems).doesNotContainAnyElementsOf(firstPageItems);
        assertThat(secondPageItems).doesNotContain(newerTicket);
    }
}
