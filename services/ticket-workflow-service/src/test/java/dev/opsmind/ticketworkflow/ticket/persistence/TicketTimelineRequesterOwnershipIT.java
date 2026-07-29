package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §6: Employee Timeline authorization is {@code requester_id = principalSubject}; cross-user access is a safe 404. */
@Tag("integration")
class TicketTimelineRequesterOwnershipIT extends AbstractTicketTimelineIT {

    @Test
    void owningEmployeeShouldSeeTheTimeline() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);

        ResponseEntity<String> response = getTimeline(ticketId, employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyAsJson(response).get("viewType").asText()).isEqualTo("EMPLOYEE_PUBLIC_VIEW");
        assertThat(bodyAsJson(response).get("items").get(0).get("itemType").asText()).isEqualTo("TICKET_CREATED");
    }

    @Test
    void nonOwningEmployeeShouldReceiveNotFound() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);

        ResponseEntity<String> response = getTimeline(ticketId, employeeToken("employee-999"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bodyAsJson(response).get("error").get("code").asText()).isEqualTo("TICKET_NOT_FOUND");
    }

    @Test
    void nonOwningEmployeeShouldNotLearnWhetherInternalItemsExist() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);
        seedInternalSupportNote(ticketId, "internal note", DEFAULT_CREATED_AT.plusSeconds(60));

        ResponseEntity<String> response = getTimeline(ticketId, employeeToken("employee-999"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("internal note");
    }

    @Test
    void missingTicketShouldReceiveNotFound() {
        ResponseEntity<String> response = getTimeline(UUID.randomUUID(), employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bodyAsJson(response).get("error").get("code").asText()).isEqualTo("TICKET_NOT_FOUND");
    }
}
