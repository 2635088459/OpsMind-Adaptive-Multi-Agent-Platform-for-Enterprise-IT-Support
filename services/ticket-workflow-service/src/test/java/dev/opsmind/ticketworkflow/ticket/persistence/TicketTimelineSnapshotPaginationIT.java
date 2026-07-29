package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §13: the first page fixes {@code snapshotAt}; every later page
 * in the same cursor session carries that exact snapshot forward rather than
 * recomputing it against the current wall clock.
 */
@Tag("integration")
class TicketTimelineSnapshotPaginationIT extends AbstractTicketTimelineIT {

    @Test
    void secondPageShouldCarryTheIdenticalSnapshotAsTheFirstPage() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "message 1", DEFAULT_CREATED_AT.plusSeconds(60));
        seedPublicSupportMessage(ticketId, "message 2", DEFAULT_CREATED_AT.plusSeconds(120));
        String token = employeeToken(DEFAULT_REQUESTER);

        ResponseEntity<String> firstPageResponse = getTimeline(ticketId, token, Map.of("limit", "1"));
        assertThat(firstPageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode firstPage = bodyAsJson(firstPageResponse);
        assertThat(firstPage.get("page").get("hasMore").asBoolean()).isTrue();
        String snapshotAt = firstPage.get("page").get("snapshotAt").asText();
        String cursor = firstPage.get("page").get("nextCursor").asText();

        ResponseEntity<String> secondPageResponse = getTimeline(ticketId, token, Map.of("limit", "1", "cursor", cursor));
        assertThat(secondPageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode secondPage = bodyAsJson(secondPageResponse);

        assertThat(secondPage.get("page").get("snapshotAt").asText()).isEqualTo(snapshotAt);
        assertThat(secondPage.get("page").get("consistency").asText()).isEqualTo("SNAPSHOT");
    }
}
