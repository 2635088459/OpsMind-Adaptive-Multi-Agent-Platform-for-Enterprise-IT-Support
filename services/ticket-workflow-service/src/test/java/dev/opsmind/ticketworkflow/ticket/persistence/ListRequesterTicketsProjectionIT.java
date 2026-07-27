package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §10: the summary projection returns exactly the allowed fields. */
@Tag("integration")
class ListRequesterTicketsProjectionIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldProjectAllowedSummaryFieldsOnly() {
        Instant createdAt = Instant.parse("2026-07-23T16:30:00Z");
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", createdAt);

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = bodyAsJson(response).get("items").get(0);
        assertThat(item.get("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(item.get("displayId").asText()).startsWith("INC-");
        assertThat(item.get("title").asText()).isEqualTo("Cannot sign in to Housing Portal");
        assertThat(item.get("applicationCode").asText()).isEqualTo("HOUSING_PORTAL");
        assertThat(item.get("status").asText()).isEqualTo("NEW");
        assertThat(item.get("priority").asText()).isEqualTo("UNASSIGNED");
        assertThat(item.get("version").asLong()).isZero();
        assertThat(item.has("description")).isFalse();
        assertThat(item.has("requesterId")).isFalse();
    }
}
