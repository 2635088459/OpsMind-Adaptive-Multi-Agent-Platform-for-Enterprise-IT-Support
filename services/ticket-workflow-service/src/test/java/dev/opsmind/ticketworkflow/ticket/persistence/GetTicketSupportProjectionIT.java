package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-002 §13: Support projection adds requesterRef, assignment, resolutionCycle, and SLA policy. */
@Tag("integration")
class GetTicketSupportProjectionIT extends AbstractGetTicketIT {

    @Test
    void shouldReturnSupportProjectionForAuthorizedQueue() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = bodyAsJson(response);
        assertThat(body.get("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(body.get("requesterRef").asText()).isNotEqualTo(DEFAULT_REQUESTER);
        assertThat(body.get("requesterRef").asText()).startsWith("hmac-sha256:");
        assertThat(body.get("assignment").get("queue").asText()).isEqualTo("HOUSING_PORTAL");
        assertThat(body.get("resolutionCycle").get("cycleNumber").asInt()).isEqualTo(1);
        assertThat(body.get("resolutionCycle").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("sla").get("policyId").asText()).isEqualTo("SLA-STANDARD-P2");
        assertThat(body.toString()).doesNotContain(DEFAULT_REQUESTER);
    }
}
