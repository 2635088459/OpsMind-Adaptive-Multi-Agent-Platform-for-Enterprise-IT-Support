package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-002 §12: Employee projection returns the correct fields and no internal ones. */
@Tag("integration")
class GetTicketEmployeeProjectionIT extends AbstractGetTicketIT {

    @Test
    void shouldReturnEmployeeProjectionForOwnedTicket() {
        UUID ticketId = seedTicket();

        ResponseEntity<String> response = getTicket(ticketId, employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getFirst("Vary")).isEqualTo("Authorization");

        JsonNode body = bodyAsJson(response);
        assertThat(body.get("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(body.get("title").asText()).isEqualTo("Cannot sign in to Housing Portal");
        assertThat(body.get("description").asText()).isEqualTo("Duo keeps asking me to enroll again.");
        assertThat(body.get("applicationCode").asText()).isEqualTo("HOUSING_PORTAL");
        assertThat(body.get("status").asText()).isEqualTo("NEW");
        assertThat(body.get("priority").asText()).isEqualTo("UNASSIGNED");
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.has("requesterRef")).isFalse();
        assertThat(body.has("assignment")).isFalse();
        assertThat(body.has("resolutionCycle")).isFalse();
        assertThat(body.get("sla").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("links").get("self").asText()).isEqualTo("/api/v1/tickets/" + ticketId);
    }
}
